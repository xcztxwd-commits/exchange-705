package com.gtcfesk.exchange.support;

import com.gtcfesk.exchange.entity.AdminMenu;
import com.gtcfesk.exchange.entity.MenuAction;
import com.gtcfesk.exchange.repository.AdminMenuRepository;
import com.gtcfesk.exchange.repository.MenuActionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Add capability definitions only; never silently grant existing roles more access. */
@Component @Order(110) @RequiredArgsConstructor
public class SupportPermissionCatalog implements CommandLineRunner {
    private final AdminMenuRepository menus;
    private final MenuActionRepository actions;
    @Override @Transactional public void run(String... args) {
        AdminMenu group = menu("group_communication", "客服与消息", "directory", 0L, null, 80);
        group.setIcon("ChatDotRound");
        register(group, "support", "客服工作台", "/support", 0, new String[][]{
            {"detail","查看自己接待的聊天"},{"claim","排队接待与上线"},{"reply","发送文字"},{"image","收发图片"},
            {"close","结束会话"},{"transfer","转接会话"},{"audit","监督所有聊天（仅超级管理员）"},{"export","导出证据（仅超级管理员）"}});
        register(group, "inbox", "站内信管理", "/inbox", 1, new String[][]{{"send","发送站内信"}});
        register(group, "support_settings", "客服与消息设置", "/support-settings", 2, new String[][]{{"save","切换渠道、开关与欢迎语"}});
    }
    private void register(AdminMenu group, String code, String title, String path, int order, String[][] capabilities) {
        AdminMenu parent = menu(code, title, "menu", group.getId(), path, order);
        int index = 0;
        for (String[] c : capabilities) {
            menu(code+":"+c[0], c[1], "button", parent.getId(), null, index);
            MenuAction action = actions.findByMenuIdAndActionCode(parent.getId(), c[0]);
            if (action == null) { action = new MenuAction(); action.setMenuId(parent.getId()); action.setActionCode(c[0]); }
            action.setActionName(c[1]); action.setSortOrder(index++); actions.save(action);
        }
    }
    private AdminMenu menu(String code, String title, String type, Long parent, String path, int order) {
        AdminMenu row = menus.findByMenuCode(code).orElseGet(AdminMenu::new);
        row.setMenuCode(code); row.setMenuName(title); row.setMenuType(type); row.setParentId(parent); row.setPath(path); row.setSortOrder(order);
        return menus.save(row);
    }
}
