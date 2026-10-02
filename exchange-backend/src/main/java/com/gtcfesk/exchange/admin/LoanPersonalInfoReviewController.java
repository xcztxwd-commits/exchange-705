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
    private void auditControl(String action,String object,String detail,String reason){if(com.gtcfesk.exchange.control.ControlIdentity.isAccess())controlAudit.recordCurrent(action,object,detail,reason); }
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService controlAudit;
    
    private final LoanPersonalInfoRepository loanPersonalInfoRepository;
    private final UserAccountRepository userAccountRepository;
    private final JwtUtil jwtUtil;
    private final com.gtcfesk.exchange.user.LoanPersonalInfoService loanPersonalInfoService;
    
    @GetMapping("/list")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "loan_personal_info_review", action = "")
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
                list = loanPersonalInfoRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), status);
            } else {
                list = loanPersonalInfoRepository.findAllByTenantIdOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
            }
            
            // 如果指定了代理ID（代理登录或管理员筛选），只返回该代理下级用户的记录
            if (targetAgentId != null) {
                List<UserAccount> subordinates = userAccountRepository.findByTenantIdAndParentUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), targetAgentId);
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
                Optional<UserAccount> userOpt = userAccountRepository.findByTenantIdAndEmail(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userEmail.trim());
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
                AdminUserIdentity.put(infoMap, null);
                infoMap.put("realName", info.getRealName());
                infoMap.put("idNumber", info.getIdNumber());
                infoMap.put("phone", info.getPhone());
                infoMap.put("address", info.getAddress());
                infoMap.put("idFrontImage", info.getIdFrontImage());
                infoMap.put("idBackImage", info.getIdBackImage());
                infoMap.put("handheldImage", info.getHandheldImage());
                infoMap.put("status", info.getStatus());
                infoMap.put("reviewRemark", info.getReviewRemark());
                infoMap.put("createdAt", info.getCreatedAt());
                infoMap.put("reviewedAt", info.getReviewedAt());
                
                // 填充代理信息和用户备注
                UserAccount user = userAccountRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), info.getUserId()).orElse(null);
                if (user != null) {
                    // 添加用户备注
                    AdminUserIdentity.put(infoMap, user);
                    
                    // 填充代理信息
                    if (user.getParentUserId() != null) {
                        UserAccount agent = userAccountRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), user.getParentUserId()).orElse(null);
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
                    AdminUserIdentity.put(infoMap, null);
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
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
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
    @com.gtcfesk.exchange.config.AdminPermission(menu = "loan_personal_info_review", action = "approve_loan_personal_info")
    public ResponseEntity<?> approvePersonalInfo(
            Authentication auth,
            @PathVariable Long id) {
        try {
            LoanPersonalInfo info = java.util.Optional.ofNullable(com.gtcfesk.exchange.tenant.TenantEntities.find(em,LoanPersonalInfo.class,id,javax.persistence.LockModeType.PESSIMISTIC_WRITE))
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
            
            loanPersonalInfoService.validateForReview(info);
            info.setStatus("APPROVED");
            info.setReviewedBy(com.gtcfesk.exchange.control.ControlIdentity.isAccess()?null:reviewerId);
            info.setReviewedAt(LocalDateTime.now());
            loanPersonalInfoRepository.save(info);
            auditControl("LOAN_PERSONAL_REVIEW",String.valueOf(id),"status="+info.getStatus(),info.getReviewRemark());
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "审核通过");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "操作失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    @PostMapping("/reject/{id}")
    @Transactional
    @com.gtcfesk.exchange.config.AdminPermission(menu = "loan_personal_info_review", action = "reject_loan_personal_info")
    public ResponseEntity<?> rejectPersonalInfo(
            Authentication auth,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> request) {
        try {
            LoanPersonalInfo info = java.util.Optional.ofNullable(com.gtcfesk.exchange.tenant.TenantEntities.find(em,LoanPersonalInfo.class,id,javax.persistence.LockModeType.PESSIMISTIC_WRITE))
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
            info.setReviewedBy(com.gtcfesk.exchange.control.ControlIdentity.isAccess()?null:reviewerId);
            info.setReviewedAt(LocalDateTime.now());
            if (request != null && request.containsKey("remark")) {
                info.setReviewRemark(request.get("remark"));
            }
            loanPersonalInfoRepository.save(info);
            auditControl("LOAN_PERSONAL_REVIEW",String.valueOf(id),"status="+info.getStatus(),info.getReviewRemark());
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "审核拒绝");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "操作失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
}



