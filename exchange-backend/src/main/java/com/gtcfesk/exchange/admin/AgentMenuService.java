package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.AdminMenu;
import com.gtcfesk.exchange.entity.UserMenu;
import com.gtcfesk.exchange.repository.AdminMenuRepository;
import com.gtcfesk.exchange.repository.UserMenuRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AgentMenuService {

    @Autowired
    private UserMenuRepository userMenuRepository;

    @Autowired
    private AdminMenuRepository adminMenuRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 获取代理的菜单权限
     */
    public List<AdminMenu> getAgentMenus(Long agentId) {
        List<UserMenu> userMenus = userMenuRepository.findByUserId(agentId);
        List<Long> menuIds = userMenus.stream()
                .map(UserMenu::getMenuId)
                .collect(Collectors.toList());
        
        if (menuIds.isEmpty()) {
            return new ArrayList<>();
        }
        
        return adminMenuRepository.findAllById(menuIds).stream()
                .filter(menu -> "active".equals(menu.getStatus()))
                .collect(Collectors.toList());
    }

    /**
     * 获取代理的菜单树（包含父子关系）
     */
    public List<AdminMenu> getAgentMenuTree(Long agentId) {
        List<AdminMenu> agentMenus = getAgentMenus(agentId);
        
        System.out.println("[AgentMenuService] 代理ID: " + agentId + ", 分配的菜单数量: " + agentMenus.size());
        
        // 如果代理没有分配菜单，返回空列表
        if (agentMenus.isEmpty()) {
            System.out.println("[AgentMenuService] 代理没有分配菜单，返回空列表");
            return new ArrayList<>();
        }
        
        // 构建菜单树（只显示分配给代理的菜单，不自动包含父菜单）
        Map<Long, AdminMenu> menuMap = new HashMap<>();
        List<AdminMenu> rootMenus = new ArrayList<>();
        
        // 将所有代理菜单放入Map，并初始化children
        for (AdminMenu menu : agentMenus) {
            menuMap.put(menu.getId(), menu);
            menu.setChildren(new ArrayList<>());
        }
        
        // 构建树结构（只处理分配给代理的菜单之间的关系）
        for (AdminMenu menu : agentMenus) {
            if (menu.getParentId() == null || menu.getParentId() == 0 || menu.getParentId() == 0L) {
                // 顶级菜单
                rootMenus.add(menu);
            } else {
                // 子菜单：查找父菜单（只在代理的菜单列表中查找）
                AdminMenu parent = menuMap.get(menu.getParentId());
                if (parent != null) {
                    // 父菜单也在代理的菜单列表中
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(menu);
                } else {
                    // 父菜单不在代理的菜单列表中，直接作为根菜单显示
                    rootMenus.add(menu);
                }
            }
        }
        
        System.out.println("[AgentMenuService] 构建的菜单树，根菜单数量: " + rootMenus.size());
        return rootMenus;
    }

    /**
     * 为代理分配菜单权限
     */
    @Transactional
    public void assignMenus(Long agentId, List<Long> menuIds) {
        // 删除现有权限
        userMenuRepository.deleteByUserId(agentId);
        
        // 确保删除操作立即生效（刷新到数据库）
        entityManager.flush();
        
        // 添加新权限
        if (menuIds != null && !menuIds.isEmpty()) {
            List<UserMenu> userMenusToSave = new ArrayList<>();
            for (Long menuId : menuIds) {
                // 验证菜单是否存在
                if (adminMenuRepository.findById(menuId).isPresent()) {
                    // 检查是否已存在（防止重复）
                    if (!userMenuRepository.existsByUserIdAndMenuId(agentId, menuId)) {
                        UserMenu userMenu = new UserMenu();
                        userMenu.setUserId(agentId);
                        userMenu.setMenuId(menuId);
                        userMenusToSave.add(userMenu);
                    }
                }
            }
            // 批量保存
            if (!userMenusToSave.isEmpty()) {
                userMenuRepository.saveAll(userMenusToSave);
            }
        }
    }

    /**
     * 获取代理的菜单ID列表
     */
    public List<Long> getAgentMenuIds(Long agentId) {
        List<UserMenu> userMenus = userMenuRepository.findByUserId(agentId);
        return userMenus.stream()
                .map(UserMenu::getMenuId)
                .collect(Collectors.toList());
    }
}

