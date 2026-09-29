package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/** No cross-user cache: permission revocations apply on the next request. */
@Service
@RequiredArgsConstructor
public class AdminPermissionService {
    private final AdminUserRepository admins;
    private final AdminRoleRepository roles;
    private final AdminRoleMenuRepository roleMenus;
    private final AdminMenuRepository menus;
    private final UserMenuRepository userMenus;
    private final UserActionRepository userActions;

    public static String normalize(String code) { return code.replace('-', '_'); }
    public boolean isSuper() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream().anyMatch(r -> r.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
    public Long agentId() { return com.gtcfesk.exchange.config.BackendAccess.agentId(); }
    private Set<Long> grantedIds() {
        Long agent = agentId();
        if (agent != null) return userMenus.findByUserId(agent).stream().map(UserMenu::getMenuId).collect(Collectors.toSet());
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.getAuthorities().stream().anyMatch(r -> r.getAuthority().equals("ROLE_ADMIN"))) return Collections.emptySet();
        AdminUser admin = admins.findById(Long.valueOf(a.getName())).orElse(null);
        if (admin == null || !Boolean.TRUE.equals(admin.getEnabled())) return Collections.emptySet();
        AdminRole role = roles.findByRoleCode(admin.getRole()).orElse(null);
        if (role == null || !"active".equals(role.getStatus())) return Collections.emptySet();
        return roleMenus.findByRoleId(role.getId()).stream().map(AdminRoleMenu::getMenuId).collect(Collectors.toSet());
    }
    public Map<String,Object> current() {
        boolean superUser = isSuper();
        Set<Long> ids = superUser ? Collections.emptySet() : grantedIds();
        List<AdminMenu> active = menus.findByStatusOrderBySortOrderAsc("active");
        Map<String,List<String>> actions = new LinkedHashMap<>();
        List<AdminMenu> visible = new ArrayList<>();
        Map<Long,AdminMenu> byId = active.stream().collect(Collectors.toMap(AdminMenu::getId, m -> m));
        for (AdminMenu m : active) {
            if (!"menu".equals(m.getMenuType()) || m.getPath() == null || m.getPath().isEmpty()) continue;
            if (!superUser && ("website_security".equals(normalize(m.getMenuCode())) || !ids.contains(m.getId()))) continue;
            if (agentId() != null && Arrays.asList("roles", "admin_list", "settings", "website_security", "support", "inbox", "support_settings").contains(normalize(m.getMenuCode()))) continue;
            AdminMenu parent = byId.get(m.getParentId());
            if (m.getParentId() != null && m.getParentId() != 0 && parent == null) continue;
            visible.add(m);
            List<String> allowed = new ArrayList<>();
            if (superUser) allowed.add("*");
            else if (agentId() != null) {
                Set<String> registered = active.stream().filter(b -> "button".equals(b.getMenuType()) && m.getId().equals(b.getParentId()))
                    .map(b -> b.getMenuCode().substring(b.getMenuCode().indexOf(':') + 1)).collect(Collectors.toSet());
                userActions.findByUserIdAndMenuId(agentId(), m.getId()).stream().map(UserAction::getActionCode)
                    .filter(registered::contains).forEach(allowed::add);
            } else {
                for (AdminMenu b : active) if ("button".equals(b.getMenuType()) && m.getId().equals(b.getParentId()) && ids.contains(b.getId()))
                    allowed.add(b.getMenuCode().substring(b.getMenuCode().indexOf(':') + 1));
            }
            if (!superUser && "support".equals(normalize(m.getMenuCode()))) allowed.removeAll(Arrays.asList("audit", "export"));
            if (!superUser) allowed.removeAll(Arrays.asList("manual_order", "abnormal_delete", "batch_ip", "set_agent", "unset_agent", "defaults"));
            if (agentId() != null) allowed.removeAll(Arrays.asList("delete_order", "restore_order", "calculate", "payout_all"));
            actions.put(normalize(m.getMenuCode()), allowed);
        }
        Set<Long> parents = visible.stream().map(AdminMenu::getParentId).collect(Collectors.toSet());
        List<AdminMenu> groups = active.stream().filter(m -> "directory".equals(m.getMenuType()) && parents.contains(m.getId())).collect(Collectors.toList());
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("success", true); out.put("superAdmin", superUser); out.put("menus", visible); out.put("groups", groups); out.put("actions", actions);
        return out;
    }
    @SuppressWarnings("unchecked")
    public boolean can(String menu, String action) {
        if (isSuper()) return true;
        Map<String,List<String>> allowed = (Map<String,List<String>>)current().get("actions");
        List<String> list = allowed.get(normalize(menu));
        return list != null && (action == null || action.isEmpty() || "view".equals(action) || list.contains(action));
    }
    public void require(String menu, String action) {
        if (!can(menu, action)) throw new AccessDeniedException("无权执行此操作");
    }
    public void requireAny(String... codes) {
        for (String code : codes) {
            String[] parts = code.split(":", 2);
            if (can(parts[0], parts.length == 2 ? parts[1] : "")) return;
        }
        throw new AccessDeniedException("无权执行此操作");
    }
    /** Managing old bindings is not granting them again. Only effective powers set the delegation ceiling. */
    public void validateManagedGrants(Collection<Long> existing) {
        if (isSuper()) return;
        for (AdminMenu menu : menus.findAllById(new HashSet<>(existing))) {
            if ("directory".equals(menu.getMenuType()) || !activePath(menu)) continue;
            if ("button".equals(menu.getMenuType())) {
                AdminMenu parent = menus.findById(menu.getParentId()).orElseThrow(IllegalArgumentException::new);
                require(parent.getMenuCode(), menu.getMenuCode().substring(menu.getMenuCode().indexOf(':') + 1));
            } else require(menu.getMenuCode(), "");
        }
    }
    private boolean activePath(AdminMenu node) {
        Set<Long> visited = new HashSet<>();
        while (node != null && visited.add(node.getId())) {
            if (!"active".equals(node.getStatus())) return false;
            if (node.getParentId() == null || node.getParentId() == 0) return true;
            node = menus.findById(node.getParentId()).orElse(null);
        }
        return false;
    }
    public void validateGrant(Collection<Long> requested) {
        Set<Long> unique = new HashSet<>(requested);
        List<AdminMenu> found = menus.findAllById(unique);
        if (found.size() != unique.size()) throw new IllegalArgumentException("权限包含不存在的菜单");
        for (AdminMenu menu : found) {
            if (!activePath(menu)) throw new IllegalArgumentException("不能分配已停用或层级失效的权限");
            if ("directory".equals(menu.getMenuType())) continue;
            if ("button".equals(menu.getMenuType())) {
                if (!unique.contains(menu.getParentId())) throw new IllegalArgumentException("分配按钮前必须选择所属菜单");
                AdminMenu parent = menus.findById(menu.getParentId()).orElseThrow(IllegalArgumentException::new);
                require(parent.getMenuCode(), menu.getMenuCode().substring(menu.getMenuCode().indexOf(':') + 1));
            } else require(menu.getMenuCode(), "");
        }
    }
}
