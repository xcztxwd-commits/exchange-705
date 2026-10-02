package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.MenuAction;
import com.gtcfesk.exchange.entity.UserAction;
import com.gtcfesk.exchange.repository.MenuActionRepository;
import com.gtcfesk.exchange.repository.UserActionRepository;
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
public class AgentActionService {
    @Autowired private com.gtcfesk.exchange.repository.UserAccountRepository scopedUsers;
    @Autowired private AdminPermissionService scopedPermissions;
    private void auditControl(String action,String object,String detail,String reason){if(com.gtcfesk.exchange.control.ControlIdentity.isAccess())controlAudit.recordCurrent(action,object,detail,reason); }
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService controlAudit;

    @Autowired private com.gtcfesk.exchange.repository.AdminMenuRepository menus;
    @Autowired private com.gtcfesk.exchange.repository.UserMenuRepository userMenus;

    @Autowired
    private MenuActionRepository menuActionRepository;

    @Autowired
    private UserActionRepository userActionRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 获取菜单的所有操作
     */
    public List<MenuAction> getMenuActions(Long menuId) {
        return menuActionRepository.findByMenuIdOrderBySortOrderAsc(menuId);
    }

    /**
     * 获取代理在某个菜单下的操作权限
     */
    public List<String> getAgentActions(Long agentId, Long menuId) {
        List<UserAction> userActions = userActionRepository.findByTenantIdAndUserIdAndMenuId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), agentId, menuId);
        return userActions.stream()
                .map(UserAction::getActionCode)
                .collect(Collectors.toList());
    }

    /**
     * 获取代理的所有操作权限（按菜单分组）
     */
    public Map<Long, List<String>> getAgentAllActions(Long agentId) {
        List<UserAction> userActions = userActionRepository.findByTenantIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), agentId);
        Map<Long, List<String>> result = new HashMap<>();
        
        for (UserAction userAction : userActions) {
            Long menuId = userAction.getMenuId();
            result.computeIfAbsent(menuId, k -> new ArrayList<>()).add(userAction.getActionCode());
        }
        
        return result;
    }

    /**
     * 为代理分配操作权限
     * @param agentId 代理ID
     * @param menuId 菜单ID
     * @param actionCodes 操作代码列表
     */
    @Transactional
    public void assignActions(Long agentId, Long menuId, List<String> actionCodes) {
        if(!scopedUsers.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),agentId).map(u->"agent".equals(u.getUserType())).orElse(false))throw new IllegalArgumentException("代理不存在");
        scopedPermissions.require("agents","assign_permission");if(actionCodes==null)throw new IllegalArgumentException("actionCodes 不能为空");
        com.gtcfesk.exchange.entity.AdminMenu menu=menus.findById(menuId).orElseThrow(()->new IllegalArgumentException("菜单不存在"));
        for(String action:actionCodes){if(menuActionRepository.findByMenuIdAndActionCode(menuId,action)==null)throw new IllegalArgumentException("操作权限不存在");scopedPermissions.require(menu.getMenuCode(),action);}
        auditControl("AGENT_ACTION_GRANTS",String.valueOf(agentId),"menuId="+menuId,null);
        // 删除该菜单下的所有操作权限
        userActionRepository.deleteByUserIdAndMenuId(agentId, menuId);
        entityManager.flush();

        // 添加新权限
        if (actionCodes != null && !actionCodes.isEmpty()) {
            List<UserAction> userActionsToSave = new ArrayList<>();
            for (String actionCode : actionCodes) {
                // 验证操作是否存在
                MenuAction menuAction = menuActionRepository.findByMenuIdAndActionCode(menuId, actionCode);
                if (menuAction != null) {
                    // 检查是否已存在（防止重复）
                    if (!userActionRepository.existsByTenantIdAndUserIdAndMenuIdAndActionCode(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), agentId, menuId, actionCode)) {
                        UserAction userAction = new UserAction();
                        userAction.setUserId(agentId);
                        userAction.setMenuId(menuId);
                        userAction.setActionCode(actionCode);
                        userActionsToSave.add(userAction);
                    }
                }
            }
            // 批量保存
            if (!userActionsToSave.isEmpty()) {
                userActionRepository.saveAll(userActionsToSave);
            }
        }
    }

    /**
     * 检查代理是否有某个操作权限
     */
    public boolean hasAction(Long agentId, Long menuId, String actionCode) {
        return userActionRepository.existsByTenantIdAndUserIdAndMenuIdAndActionCode(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), agentId, menuId, actionCode);
    }

    /**
     * 检查代理是否有某个操作权限（通过菜单代码）
     */
    public boolean hasActionByMenuCode(Long agentId, String menuCode, String actionCode) {
        return menus.findByMenuCode(menuCode).map(m -> "active".equals(m.getStatus()) &&
            userMenus.existsByTenantIdAndUserIdAndMenuId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), agentId, m.getId()) && hasAction(agentId, m.getId(), actionCode)).orElse(false);
    }
}
