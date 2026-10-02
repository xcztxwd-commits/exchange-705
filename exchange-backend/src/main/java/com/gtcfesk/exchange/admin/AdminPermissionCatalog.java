package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.io.InputStream;
import java.util.*;

/** Idempotent catalog migration. Never grants newly introduced write permissions. */
@Component
@Order(100)
@RequiredArgsConstructor
public class AdminPermissionCatalog implements CommandLineRunner {
    private final AdminMenuRepository menus;
    private final MenuActionRepository actions;
    private final ObjectMapper mapper;
    @Override @Transactional
    public void run(String... args) throws Exception {
        JsonNode root;
        try (InputStream input = new ClassPathResource("admin-permissions.json").getInputStream()) { root = mapper.readTree(input); }
        Map<String,Long> groups = new HashMap<>(); int order = 0;
        for (JsonNode g : root.path("groups")) {
            AdminMenu row = upsert("group_" + g.path("code").asText(), g.path("name").asText(), "directory", 0L, null, order++);
            row.setIcon(g.path("icon").asText()); menus.save(row); groups.put(g.path("code").asText(), row.getId());
        }
        order = 0;
        for (JsonNode m : root.path("menus")) {
            String code = m.path("code").asText();
            AdminMenu row = upsert(code, m.path("name").asText(), "menu", groups.get(m.path("group").asText()), m.path("path").asText(), order++);
            Iterator<Map.Entry<String,JsonNode>> it = m.path("actions").fields(); int actionOrder = 0;
            while (it.hasNext()) {
                Map.Entry<String,JsonNode> a = it.next();
                String scoped = code + ":" + a.getKey();
                // Retain IDs of legacy button grants when the existing button belongs to this menu.
                if (!menus.findByMenuCode(scoped).isPresent()) {
                    menus.findByMenuCode(a.getKey()).filter(b -> row.getId().equals(b.getParentId()) && "button".equals(b.getMenuType()))
                        .ifPresent(b -> { b.setMenuCode(scoped); menus.save(b); });
                }
                upsert(scoped, a.getValue().asText(), "button", row.getId(), null, actionOrder);
                MenuAction action = actions.findByMenuIdAndActionCode(row.getId(), a.getKey());
                if (action == null) { action = new MenuAction(); action.setMenuId(row.getId()); action.setActionCode(a.getKey()); }
                action.setActionName(a.getValue().asText()); action.setSortOrder(actionOrder++); actions.save(action);
            }
        }
    }
    private AdminMenu upsert(String code, String name, String type, Long parent, String path, int order) {
        AdminMenu row = menus.findByMenuCode(code).orElseGet(() -> menus.findByMenuCode(code.replace('_','-')).orElse(null));
        if (row == null && "announcement".equals(code)) row = menus.findByMenuCode("announcements").orElse(null);
        if (row == null && "settings".equals(code)) row = menus.findByMenuCode("system-config").orElse(null);
        if (row == null) row = new AdminMenu();
        row.setMenuCode(code);
        row.setMenuName(name); row.setMenuType(type); row.setParentId(parent); row.setPath(path); row.setSortOrder(order);
        return menus.save(row);
    }
}
