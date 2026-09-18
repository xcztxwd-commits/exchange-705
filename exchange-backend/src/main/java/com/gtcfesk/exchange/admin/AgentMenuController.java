package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/agent-menus")
public class AgentMenuController {

    @Autowired
    private AgentMenuService agentMenuService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 获取代理的菜单权限（树形结构）
     */
    @GetMapping("/{agentId}/tree")
    public ResponseEntity<?> getAgentMenuTree(@PathVariable Long agentId) {
        List<com.gtcfesk.exchange.entity.AdminMenu> menuTree = agentMenuService.getAgentMenuTree(agentId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", menuTree);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取代理的菜单权限（ID列表）
     */
    @GetMapping("/{agentId}")
    public ResponseEntity<?> getAgentMenus(@PathVariable Long agentId) {
        List<Long> menuIds = agentMenuService.getAgentMenuIds(agentId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("menuIds", menuIds);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取当前登录代理的菜单权限（用于前端菜单显示）
     */
    @GetMapping("/current")
    public ResponseEntity<?> getCurrentAgentMenus(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            Long agentId = null;
            
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                
                // 如果是mock token
                if (token.startsWith("mock-")) {
                    String userIdStr = token.substring(5);
                    if (userIdStr.startsWith("agent-")) {
                        agentId = Long.parseLong(userIdStr.substring(6));
                        System.out.println("[AgentMenuController] 从mock token提取agentId: " + agentId);
                    }
                } else {
                    // 解析JWT token
                    try {
                        Claims claims = jwtUtil.parse(token);
                        String currentUserType = (String) claims.get("userType");
                        Object userIdObj = claims.get("id");
                        String subject = claims.getSubject();
                        
                        System.out.println("[AgentMenuController] JWT解析 - userType: " + currentUserType + ", id: " + userIdObj + ", subject: " + subject);
                        
                        // 先尝试从claims中获取
                        if ("agent".equals(currentUserType) && userIdObj != null) {
                            if (userIdObj instanceof Number) {
                                agentId = ((Number) userIdObj).longValue();
                            } else {
                                agentId = Long.parseLong(userIdObj.toString());
                            }
                            System.out.println("[AgentMenuController] 从claims提取agentId: " + agentId);
                        } else if (subject != null && subject.startsWith("agent-")) {
                            // 从subject中提取
                            agentId = Long.parseLong(subject.substring(6));
                            System.out.println("[AgentMenuController] 从subject提取agentId: " + agentId);
                        }
                    } catch (Exception e) {
                        System.err.println("[AgentMenuController] JWT解析失败: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            } else {
                System.out.println("[AgentMenuController] 没有Authorization header");
            }
            
            System.out.println("[AgentMenuController] 最终agentId: " + agentId);
            
            if (agentId != null) {
                List<com.gtcfesk.exchange.entity.AdminMenu> menuTree = agentMenuService.getAgentMenuTree(agentId);
                System.out.println("[AgentMenuController] 获取到菜单数量: " + (menuTree != null ? menuTree.size() : 0));
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("list", menuTree);
                return ResponseEntity.ok(result);
            } else {
                // 不是代理用户或未找到代理ID，返回空列表
                System.out.println("[AgentMenuController] 未找到代理ID，返回空菜单");
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("list", new java.util.ArrayList<>());
                return ResponseEntity.ok(result);
            }
        } catch (Exception e) {
            System.err.println("[AgentMenuController] 获取菜单异常: " + e.getMessage());
            e.printStackTrace();
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "获取菜单失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }
}

