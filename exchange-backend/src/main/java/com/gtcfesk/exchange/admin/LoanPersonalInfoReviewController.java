package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.LoanPersonalInfo;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.LoanPersonalInfoRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/loan/personal-info")
@RequiredArgsConstructor
public class LoanPersonalInfoReviewController {
    
    private final LoanPersonalInfoRepository loanPersonalInfoRepository;
    private final UserAccountRepository userAccountRepository;
    private final JwtUtil jwtUtil;
    
    @GetMapping("/list")
    public ResponseEntity<?> getPersonalInfoList(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) Long filterAgentId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            Long agentId = extractAgentId(authHeader);
            // 如果是管理员传入了筛选代理ID，使用筛选的代理ID；否则使用登录的代理ID
            Long targetAgentId = (agentId == null && filterAgentId != null) ? filterAgentId : agentId;
            
            List<LoanPersonalInfo> list;
            if (status != null && !status.isEmpty()) {
                list = loanPersonalInfoRepository.findByStatusOrderByCreatedAtDesc(status);
            } else {
                list = loanPersonalInfoRepository.findAllByOrderByCreatedAtDesc();
            }
            
            // 如果指定了代理ID（代理登录或管理员筛选），只返回该代理下级用户的记录
            if (targetAgentId != null) {
                List<UserAccount> subordinates = userAccountRepository.findByParentUserId(targetAgentId);
                Set<Long> subordinateUserIds = subordinates.stream()
                        .map(UserAccount::getId)
                        .collect(Collectors.toSet());
                
                if (!subordinateUserIds.isEmpty()) {
                    list = list.stream()
                            .filter(info -> subordinateUserIds.contains(info.getUserId()))
                            .collect(Collectors.toList());
                } else {
                    list = new java.util.ArrayList<>();
                }
            }
            
            // 按用户ID过滤
            if (userId != null) {
                list = list.stream()
                        .filter(info -> info.getUserId() != null && info.getUserId().equals(userId))
                        .collect(Collectors.toList());
            }
            
            // 按用户邮箱过滤
            if (userEmail != null && !userEmail.trim().isEmpty()) {
                Optional<UserAccount> userOpt = userAccountRepository.findByEmail(userEmail.trim());
                if (userOpt.isPresent()) {
                    Long targetUserId = userOpt.get().getId();
                    list = list.stream()
                            .filter(info -> info.getUserId() != null && info.getUserId().equals(targetUserId))
                            .collect(Collectors.toList());
                } else {
                    // 用户不存在，返回空列表
                    list = new java.util.ArrayList<>();
                }
            }
            
            // 填充代理信息
            List<Map<String, Object>> resultList = list.stream().map(info -> {
                Map<String, Object> infoMap = new HashMap<>();
                infoMap.put("id", info.getId());
                infoMap.put("userId", info.getUserId());
                infoMap.put("realName", info.getRealName());
                infoMap.put("idNumber", info.getIdNumber());
                infoMap.put("phone", info.getPhone());
                infoMap.put("address", info.getAddress());
                infoMap.put("idFrontImage", info.getIdFrontImage());
                infoMap.put("idBackImage", info.getIdBackImage());
                infoMap.put("status", info.getStatus());
                infoMap.put("reviewRemark", info.getReviewRemark());
                infoMap.put("createdAt", info.getCreatedAt());
                infoMap.put("reviewedAt", info.getReviewedAt());
                
                // 填充代理信息和用户备注
                UserAccount user = userAccountRepository.findById(info.getUserId()).orElse(null);
                if (user != null) {
                    // 添加用户备注
                    infoMap.put("userRemark", user.getRemark());
                    
                    // 填充代理信息
                    if (user.getParentUserId() != null) {
                        UserAccount agent = userAccountRepository.findById(user.getParentUserId()).orElse(null);
                        if (agent != null) {
                            String agentName = (agent.getNickname() != null && !agent.getNickname().isEmpty()) 
                                    ? agent.getNickname() : agent.getEmail();
                            infoMap.put("agentInfo", "所属代理:" + agentName);
                        } else {
                            infoMap.put("agentInfo", null);
                        }
                    } else {
                        infoMap.put("agentInfo", null);
                    }
                } else {
                    infoMap.put("agentInfo", null);
                    infoMap.put("userRemark", null);
                }
                
                return infoMap;
            }).collect(Collectors.toList());
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", resultList);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
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
    
    @PostMapping("/approve/{id}")
    @Transactional
    public ResponseEntity<?> approvePersonalInfo(
            Authentication auth,
            @PathVariable Long id) {
        try {
            LoanPersonalInfo info = loanPersonalInfoRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("记录不存在"));
            
            if (!"PENDING".equals(info.getStatus())) {
                throw new RuntimeException("该记录已处理");
            }
            
            Long reviewerId = null;
            if (auth != null && auth.getName() != null) {
                try {
                    reviewerId = Long.parseLong(auth.getName());
                } catch (Exception e) {
                    // 忽略
                }
            }
            
            info.setStatus("APPROVED");
            info.setReviewedBy(reviewerId);
            info.setReviewedAt(LocalDateTime.now());
            loanPersonalInfoRepository.save(info);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "审核通过");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "操作失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    @PostMapping("/reject/{id}")
    @Transactional
    public ResponseEntity<?> rejectPersonalInfo(
            Authentication auth,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> request) {
        try {
            LoanPersonalInfo info = loanPersonalInfoRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("记录不存在"));
            
            if (!"PENDING".equals(info.getStatus())) {
                throw new RuntimeException("该记录已处理");
            }
            
            Long reviewerId = null;
            if (auth != null && auth.getName() != null) {
                try {
                    reviewerId = Long.parseLong(auth.getName());
                } catch (Exception e) {
                    // 忽略
                }
            }
            
            info.setStatus("REJECTED");
            info.setReviewedBy(reviewerId);
            info.setReviewedAt(LocalDateTime.now());
            if (request != null && request.containsKey("remark")) {
                info.setReviewRemark(request.get("remark"));
            }
            loanPersonalInfoRepository.save(info);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "审核拒绝");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "操作失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
}



