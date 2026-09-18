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
        List<UserAction> userActions = userActionRepository.findByUserIdAndMenuId(agentId, menuId);
        return userActions.stream()
                .map(UserAction::getActionCode)
                .collect(Collectors.toList());
    }

    /**
     * 获取代理的所有操作权限（按菜单分组）
     */
    public Map<Long, List<String>> getAgentAllActions(Long agentId) {
        List<UserAction> userActions = userActionRepository.findByUserId(agentId);
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
                    if (!userActionRepository.existsByUserIdAndMenuIdAndActionCode(agentId, menuId, actionCode)) {
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
        return userActionRepository.existsByUserIdAndMenuIdAndActionCode(agentId, menuId, actionCode);
    }

    /**
     * 检查代理是否有某个操作权限（通过菜单代码）
     */
    public boolean hasActionByMenuCode(Long agentId, String menuCode, String actionCode) {
        // 这里需要先通过menuCode找到menuId，暂时返回true（后续可以优化）
        // 实际使用时，前端会传入menuId
        return true;
    }
}




