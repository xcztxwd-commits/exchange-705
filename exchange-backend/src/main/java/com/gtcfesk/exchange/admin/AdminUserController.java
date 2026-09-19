package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.admin.dto.ResetPasswordRequest;
import com.gtcfesk.exchange.admin.dto.UpdateUserStatusRequest;
import com.gtcfesk.exchange.admin.dto.UserQueryRequest;
import com.gtcfesk.exchange.admin.dto.UpdateUserBalanceRequest;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.entity.MenuAction;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private com.gtcfesk.exchange.config.BackendAccess backendAccess;

    @Autowired
    private AgentActionService agentActionService;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/query")
    public ResponseEntity<?> queryUsers(
            @RequestBody UserQueryRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        // 从JWT token中获取当前登录用户信息
        // 如果是代理，只能查看自己的下级用户
        try {
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                
                // 如果是mock token，尝试解析
                if (token.startsWith("mock-")) {
                    String userIdStr = token.substring(5);
                    // mock-{userId} 格式，通常是管理员
                    // 如果是 agent-mock-{userId}，则需要解析
                    if (userIdStr.startsWith("agent-")) {
                        Long agentId = Long.parseLong(userIdStr.substring(6));
                        req.setAgentId(agentId);
                    }
                } else {
                    // 解析JWT token
                    try {
                        Claims claims = jwtUtil.parse(token);
                        String currentUserType = (String) claims.get("userType");
                        Object userIdObj = claims.get("id");
                        
                        if ("agent".equals(currentUserType) && userIdObj != null) {
                            Long agentId;
                            if (userIdObj instanceof Number) {
                                agentId = ((Number) userIdObj).longValue();
                            } else {
                                agentId = Long.parseLong(userIdObj.toString());
                            }
                            req.setAgentId(agentId);
                        }
                    } catch (Exception e) {
                        // JWT解析失败，尝试从subject中提取
                        // 如果subject是 agent-{userId} 格式
                        try {
                            Claims claims = jwtUtil.parse(token);
                            String subject = claims.getSubject();
                            if (subject != null && subject.startsWith("agent-")) {
                                Long agentId = Long.parseLong(subject.substring(6));
                                req.setAgentId(agentId);
                            }
                        } catch (Exception ignored) {
                            // 解析失败，忽略（可能是超级管理员）
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败，忽略（可能是超级管理员）
        }
        
        // 如果是管理员传入了筛选代理ID，使用筛选的代理ID；否则使用登录的代理ID
        if (req.getFilterAgentId() != null) {
            // 管理员筛选特定代理的下级用户
            req.setAgentId(req.getFilterAgentId());
        }
        
        Page<UserAccount> page = adminUserService.queryUsers(req);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", page.getContent());
        result.put("total", page.getTotalElements());
        result.put("page", page.getNumber());
        result.put("size", page.getSize());
        return ResponseEntity.ok(result);
    }

    @GetMapping
    public ResponseEntity<?> getUsersWithParams(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) Long filterAgentId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        UserQueryRequest req = new UserQueryRequest();
        req.setPage(page != null ? page - 1 : 0); // 前端从1开始，后端从0开始
        req.setSize(size != null ? size : 20);
        req.setUserId(userId);
        req.setKeyword(keyword);
        req.setStatus(status);
        req.setUserType(userType);
        req.setFilterAgentId(filterAgentId);
        
        // 从JWT token中获取当前登录用户信息
        // 如果是代理，只能查看自己的下级用户
        try {
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                
                // 如果是mock token，尝试解析
                if (token.startsWith("mock-")) {
                    String userIdStr = token.substring(5);
                    // mock-{userId} 格式，通常是管理员
                    // 如果是 agent-mock-{userId}，则需要解析
                    if (userIdStr.startsWith("agent-")) {
                        Long agentId = Long.parseLong(userIdStr.substring(6));
                        req.setAgentId(agentId);
                    }
                } else {
                    // 解析JWT token
                    try {
                        Claims claims = jwtUtil.parse(token);
                        String currentUserType = (String) claims.get("userType");
                        Object userIdObj = claims.get("id");
                        
                        if ("agent".equals(currentUserType) && userIdObj != null) {
                            Long agentId;
                            if (userIdObj instanceof Number) {
                                agentId = ((Number) userIdObj).longValue();
                            } else {
                                agentId = Long.parseLong(userIdObj.toString());
                            }
                            req.setAgentId(agentId);
                        }
                    } catch (Exception e) {
                        // JWT解析失败，尝试从subject中提取
                        // 如果subject是 agent-{userId} 格式
                        try {
                            Claims claims = jwtUtil.parse(token);
                            String subject = claims.getSubject();
                            if (subject != null && subject.startsWith("agent-")) {
                                Long agentId = Long.parseLong(subject.substring(6));
                                req.setAgentId(agentId);
                            }
                        } catch (Exception ignored) {
                            // 解析失败，忽略（可能是超级管理员）
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败，忽略（可能是超级管理员）
        }
        
        Page<UserAccount> pageResult = adminUserService.queryUsers(req);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", pageResult.getContent());
        result.put("total", pageResult.getTotalElements());
        result.put("page", pageResult.getNumber());
        result.put("size", pageResult.getSize());
        return ResponseEntity.ok(result);
    }


    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserDetail(
            @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        UserAccount user = adminUserService.getUserDetail(userId);
        
        // 如果是代理，验证用户是否是自己的下级
        Long agentId = extractAgentId(authHeader);
        if (agentId != null) {
            // 如果用户没有上级，或者上级不是当前代理，则无权访问
            if (user.getParentUserId() == null || !user.getParentUserId().equals(agentId)) {
                Map<String, String> result = new HashMap<>();
                result.put("message", "无权访问该用户信息");
                return ResponseEntity.status(403).body(result);
            }
        }
        
        return ResponseEntity.ok(user);
    }
    
    /**
     * 从请求头中提取代理ID
     */
    private Long extractAgentId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        
        try {
            String token = authHeader.substring(7);
            
            // 如果是mock token
            if (token.startsWith("mock-")) {
                String userIdStr = token.substring(5);
                if (userIdStr.startsWith("agent-")) {
                    return Long.parseLong(userIdStr.substring(6));
                }
                return null;
            }
            
            // 解析JWT token
            try {
                Claims claims = jwtUtil.parse(token);
                String currentUserType = (String) claims.get("userType");
                Object userIdObj = claims.get("id");
                
                if ("agent".equals(currentUserType) && userIdObj != null) {
                    if (userIdObj instanceof Number) {
                        return ((Number) userIdObj).longValue();
                    } else {
                        return Long.parseLong(userIdObj.toString());
                    }
                }
            } catch (Exception e) {
                // JWT解析失败，尝试从subject中提取
                try {
                    Claims claims = jwtUtil.parse(token);
                    String subject = claims.getSubject();
                    if (subject != null && subject.startsWith("agent-")) {
                        return Long.parseLong(subject.substring(6));
                    }
                } catch (Exception ignored) {
                    // 解析失败，忽略
                }
            }
        } catch (Exception e) {
            // 解析失败，忽略
        }
        
        return null;
    }

    @PostMapping("/resetPassword")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest req) {
        adminUserService.resetPassword(req);
        Map<String, String> result = new HashMap<>();
        result.put("message", "密码重置成功");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/updateStatus")
    public ResponseEntity<?> updateStatus(@RequestBody UpdateUserStatusRequest req) {
        adminUserService.updateStatus(req);
        Map<String, String> result = new HashMap<>();
        result.put("message", "状态更新成功");
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{userId}/status")
    public ResponseEntity<?> updateUserStatus(@PathVariable Long userId, @RequestBody Map<String, String> request) {
        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setUserId(userId);
        req.setStatus(request.get("status"));
        adminUserService.updateStatus(req);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "状态更新成功");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/updateUserType")
    public ResponseEntity<?> updateUserType(@RequestBody com.gtcfesk.exchange.admin.dto.UpdateUserTypeRequest req) {
        adminUserService.updateUserType(req);
        Map<String, String> result = new HashMap<>();
        result.put("message", "用户类型更新成功");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/updateBalance")
    public ResponseEntity<?> updateBalance(@RequestBody UpdateUserBalanceRequest req) {
        adminUserService.updateBalance(req);
        Map<String, String> result = new HashMap<>();
        result.put("message", "余额更新成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定用户的下级用户列表
     */
    @GetMapping("/{userId}/subordinates")
    public ResponseEntity<?> getSubordinates(
            @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        // 如果是代理，只能查看自己的下级用户
        Long agentId = extractAgentId(authHeader);
        if (agentId != null && !userId.equals(agentId)) {
            Map<String, String> result = new HashMap<>();
            result.put("message", "无权访问该用户的下级列表");
            return ResponseEntity.status(403).body(result);
        }
        
        List<UserAccount> subordinates = adminUserService.getSubordinates(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("list", subordinates);
        result.put("total", subordinates.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 删除用户（包括所有关联数据）
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable Long userId) {
        try {
            adminUserService.deleteUser(userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "用户删除成功");
            return ResponseEntity.ok(result);
        } catch (com.gtcfesk.exchange.common.BusinessException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "删除用户失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 为代理分配菜单权限和操作权限
     */
    @PostMapping("/{userId}/menus")
    public ResponseEntity<?> assignMenus(@PathVariable Long userId, @RequestBody Map<String, Object> request) {
        // 验证用户是否为代理
        UserAccount user = adminUserService.getUserDetail(userId);
        if (!"agent".equals(user.getUserType())) {
            Map<String, String> result = new HashMap<>();
            result.put("message", "只能为代理用户分配菜单权限");
            return ResponseEntity.badRequest().body(result);
        }

        // 获取菜单ID列表
        List<?> rawMenuIds = (List<?>) request.get("menuIds");
        List<Long> menuIds = new ArrayList<>();
        if (rawMenuIds != null) {
            for (Object obj : rawMenuIds) {
                if (obj instanceof Number) {
                    menuIds.add(((Number) obj).longValue());
                }
            }
        }

        // 分配菜单权限
        adminUserService.assignAgentMenus(userId, menuIds);

        // 分配操作权限（格式：{menuId: [actionCode1, actionCode2, ...]})
        Map<?, ?> rawActions = (Map<?, ?>) request.get("actions");
        if (rawActions != null) {
            for (Map.Entry<?, ?> entry : rawActions.entrySet()) {
                Long menuId;
                if (entry.getKey() instanceof Number) {
                    menuId = ((Number) entry.getKey()).longValue();
                } else {
                    menuId = Long.parseLong(entry.getKey().toString());
                }
                
                List<String> actionCodes = new ArrayList<>();
                if (entry.getValue() instanceof List) {
                    List<?> rawActionCodes = (List<?>) entry.getValue();
                    for (Object actionCode : rawActionCodes) {
                        if (actionCode != null) {
                            actionCodes.add(actionCode.toString());
                        }
                    }
                }
                
                agentActionService.assignActions(userId, menuId, actionCodes);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "菜单权限分配成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 获取代理的菜单权限和操作权限
     */
    @GetMapping("/{userId}/menus")
    public ResponseEntity<?> getAgentMenus(@PathVariable Long userId) {
        List<Long> menuIds = adminUserService.getAgentMenuIds(userId);
        Map<Long, List<String>> actions = agentActionService.getAgentAllActions(userId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("menuIds", menuIds);
        result.put("actions", actions);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取菜单的操作列表
     */
    @GetMapping("/menus/{menuId}/actions")
    public ResponseEntity<?> getMenuActions(@PathVariable Long menuId) {
        List<MenuAction> actions = agentActionService.getMenuActions(menuId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", actions);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有代理列表（用于筛选，只返回ID和用户名）
     */
    @GetMapping("/agents/simple")
    public ResponseEntity<?> getAgentsSimple() {
        List<UserAccount> agents = adminUserService.getAllAgents();
        List<Map<String, Object>> result = agents.stream().map(agent -> {
            Map<String, Object> agentMap = new HashMap<>();
            agentMap.put("id", agent.getId());
            agentMap.put("name", agent.getNickname() != null && !agent.getNickname().isEmpty() 
                    ? agent.getNickname() : agent.getEmail());
            agentMap.put("email", agent.getEmail());
            return agentMap;
        }).collect(java.util.stream.Collectors.toList());
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("list", result);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取用户资金明细
     */
    @GetMapping("/{userId}/fund-details")
    public ResponseEntity<?> getUserFundDetails(
            @PathVariable Long userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        // 如果是代理，验证用户是否是自己的下级
        Long agentId = extractAgentId(authHeader);
        if (agentId != null) {
            UserAccount user = adminUserService.getUserDetail(userId);
            if (user.getParentUserId() == null || !user.getParentUserId().equals(agentId)) {
                Map<String, String> result = new HashMap<>();
                result.put("message", "无权访问该用户信息");
                return ResponseEntity.status(403).body(result);
            }
        }
        
        Map<String, Object> fundDetails = adminUserService.getUserFundDetails(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", fundDetails);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取在线用户数
     */
    @GetMapping("/online-count")
    public ResponseEntity<?> getOnlineUserCount(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            Long agentId = extractAgentId(authHeader);
            int onlineCount = backendAccess.canReadMenu("users") ? adminUserService.getOnlineUserCount(agentId) : 0;
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("count", onlineCount);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 修改用户邀请码
     */
    @PostMapping("/{userId}/update-invite-code")
    public ResponseEntity<?> updateInviteCode(
            @PathVariable Long userId,
            @RequestBody Map<String, String> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        try {
            String newInviteCode = request.get("inviteCode");
            if (newInviteCode == null || newInviteCode.trim().isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "邀请码不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            
            // 如果是代理，验证用户是否是自己的下级
            Long agentId = extractAgentId(authHeader);
            if (agentId != null) {
                UserAccount user = adminUserService.getUserDetail(userId);
                if (user.getParentUserId() == null || !user.getParentUserId().equals(agentId)) {
                    Map<String, String> result = new HashMap<>();
                    result.put("message", "无权访问该用户信息");
                    return ResponseEntity.status(403).body(result);
                }
            }
            
            adminUserService.updateInviteCode(userId, newInviteCode.trim());
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "邀请码修改成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "修改失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 更新代理备注
     */
    @PostMapping("/{userId}/update-remark")
    public ResponseEntity<?> updateRemark(
            @PathVariable Long userId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String remark = request.get("remark");
            adminUserService.updateRemark(userId, remark);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "备注更新成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "更新失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(result);
        }
    }
    
    /**
     * 批量更新所有用户的IP地区信息（精确到市级别）
     * 用于更新旧数据，将"中国大陆"、"中国"等简化信息更新为详细的省市信息
     */
    @PostMapping("/batch-update-ip-regions")
    public ResponseEntity<?> batchUpdateIpRegions(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            int updatedCount = adminUserService.batchUpdateIpRegions();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "批量更新IP地区信息完成");
            result.put("updatedCount", updatedCount);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "批量更新失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            e.printStackTrace();
            return ResponseEntity.status(500).body(error);
        }
    }
}





