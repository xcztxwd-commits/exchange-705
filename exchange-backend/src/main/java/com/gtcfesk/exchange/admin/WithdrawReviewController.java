package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.entity.UserBankCard;
import com.gtcfesk.exchange.entity.WithdrawRecord;
import com.gtcfesk.exchange.repository.AdminMenuRepository;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.repository.UserBankCardRepository;
import com.gtcfesk.exchange.repository.WithdrawRecordRepository;
import io.jsonwebtoken.Claims;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/withdraw")
@RequiredArgsConstructor
public class WithdrawReviewController {
    
    private final WithdrawRecordRepository withdrawRecordRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final UserBankCardRepository userBankCardRepository;
    private final UserAccountRepository userAccountRepository;
    private final AgentActionService agentActionService;
    private final AdminMenuRepository adminMenuRepository;
    private final JwtUtil jwtUtil;
    
    /**
     * 获取提现记录列表
     */
    @GetMapping("/list")
    public ResponseEntity<?> getWithdrawList(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) Long filterAgentId, // 管理员筛选代理ID
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            Long agentId = extractAgentId(authHeader);
            // 如果是管理员传入了筛选代理ID，使用筛选的代理ID；否则使用登录的代理ID
            Long targetAgentId = (agentId == null && filterAgentId != null) ? filterAgentId : agentId;
            
            List<WithdrawRecord> records;
            if (status != null && !status.isEmpty()) {
                if (type != null && !type.isEmpty()) {
                    records = withdrawRecordRepository.findByStatusAndTypeOrderByCreatedAtDesc(status, type);
                } else {
                    records = withdrawRecordRepository.findByStatusOrderByCreatedAtDesc(status);
                }
            } else {
                records = withdrawRecordRepository.findAllByOrderByCreatedAtDesc();
            }
            
            // 如果指定了代理ID（代理登录或管理员筛选），只返回该代理下级用户的提现记录
            if (targetAgentId != null) {
                List<UserAccount> subordinates = userAccountRepository.findByParentUserId(targetAgentId);
                Set<Long> subordinateUserIds = subordinates.stream()
                        .map(UserAccount::getId)
                        .collect(Collectors.toSet());
                
                if (!subordinateUserIds.isEmpty()) {
                    records = records.stream()
                            .filter(record -> subordinateUserIds.contains(record.getUserId()))
                            .collect(Collectors.toList());
                } else {
                    records = new java.util.ArrayList<>();
                }
            }
            
            // 按用户ID过滤
            if (userId != null) {
                records = records.stream()
                        .filter(record -> record.getUserId() != null && record.getUserId().equals(userId))
                        .collect(Collectors.toList());
            }
            
            // 按用户邮箱过滤
            if (userEmail != null && !userEmail.trim().isEmpty()) {
                Optional<UserAccount> userOpt = userAccountRepository.findByEmail(userEmail.trim());
                if (userOpt.isPresent()) {
                    Long targetUserId = userOpt.get().getId();
                    records = records.stream()
                            .filter(record -> record.getUserId() != null && record.getUserId().equals(targetUserId))
                            .collect(Collectors.toList());
                } else {
                    // 用户不存在，返回空列表
                    records = new java.util.ArrayList<>();
                }
            }
            
            // 如果是银行卡类型，关联查询银行卡信息，并填充代理信息
            List<Map<String, Object>> result = records.stream().map(record -> {
                Map<String, Object> recordMap = new HashMap<>();
                recordMap.put("id", record.getId());
                recordMap.put("userId", record.getUserId());
                recordMap.put("type", record.getType());
                recordMap.put("network", record.getNetwork());
                recordMap.put("amount", record.getAmount());
                recordMap.put("actualAmount", record.getActualAmount());
                recordMap.put("fee", record.getFee());
                recordMap.put("address", record.getAddress());
                recordMap.put("remark", record.getRemark());
                recordMap.put("status", record.getStatus());
                recordMap.put("reviewRemark", record.getReviewRemark());
                recordMap.put("createdAt", record.getCreatedAt());
                recordMap.put("reviewedAt", record.getReviewedAt());
                
                // 填充代理信息
                fillAgentInfo(record);
                recordMap.put("agentInfo", record.getAgentInfo());
                
                // 添加用户备注
                if (record.getUserId() != null) {
                    UserAccount user = userAccountRepository.findById(record.getUserId()).orElse(null);
                    if (user != null) {
                        recordMap.put("userRemark", user.getRemark());
                    }
                }
                
                // 如果是银行卡类型，查询银行卡详细信息
                if ("bank".equals(record.getType())) {
                    List<UserBankCard> bankCards = userBankCardRepository.findByUserId(record.getUserId());
                    Optional<UserBankCard> matchedCard = bankCards.stream()
                            .filter(card -> record.getAddress().equals(card.getRecipientAccount()))
                            .findFirst();
                    
                    if (matchedCard.isPresent()) {
                        UserBankCard card = matchedCard.get();
                        Map<String, Object> bankInfo = new HashMap<>();
                        bankInfo.put("recipientName", card.getRecipientName());
                        bankInfo.put("bankName", card.getBankName());
                        bankInfo.put("recipientAccount", card.getRecipientAccount());
                        recordMap.put("bankInfo", bankInfo);
                    }
                }
                
                return recordMap;
            }).collect(Collectors.toList());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("error", "获取列表失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 填充提现记录的代理信息
     */
    private void fillAgentInfo(WithdrawRecord record) {
        if (record.getUserId() == null) {
            return;
        }
        
        // 查找用户信息
        UserAccount user = userAccountRepository.findById(record.getUserId()).orElse(null);
        if (user == null) {
            return;
        }
        
        // 如果用户有上级代理，填充代理信息
        if (user.getParentUserId() != null) {
            UserAccount agent = userAccountRepository.findById(user.getParentUserId()).orElse(null);
            if (agent != null) {
                // 格式：所属代理:用户名（优先使用昵称，没有则使用邮箱）
                String agentName = agent.getNickname();
                if (agentName == null || agentName.isEmpty()) {
                    agentName = agent.getEmail();
                }
                record.setAgentInfo("所属代理:" + agentName);
            }
        }
    }
    
    /**
     * 审核通过
     */
    @PostMapping("/{id}/approve")
    @Transactional
    public ResponseEntity<?> approveWithdraw(
            @PathVariable Long id, 
            @RequestBody(required = false) ApproveRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            // 检查是否是代理，如果是代理需要检查权限
            Long agentId = extractAgentId(authHeader);
            if (agentId != null) {
                // 检查代理是否有提现审核权限
                Long menuId = getMenuIdByCode("withdraw_review");
                if (menuId == null) {
                    menuId = getMenuIdByCode("withdraw-review");
                }
                if (menuId != null && !agentActionService.hasAction(agentId, menuId, "approve_withdraw")) {
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("error", "代理账号无权进行审核操作");
                    return ResponseEntity.status(403).body(resp);
                }
            }
            Optional<WithdrawRecord> recordOpt = withdrawRecordRepository.findById(id);
            if (!recordOpt.isPresent()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("error", "提现记录不存在");
                return ResponseEntity.badRequest().body(resp);
            }
            
            WithdrawRecord record = recordOpt.get();
            if (!"PENDING".equals(record.getStatus())) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("error", "该记录已处理，无法重复审核");
                return ResponseEntity.badRequest().body(resp);
            }
            
            // 更新记录状态
            record.setStatus("APPROVED");
            record.setReviewRemark(req != null ? req.getRemark() : null);
            record.setReviewedAt(LocalDateTime.now());
            withdrawRecordRepository.save(record);
            
            // 实际到账金额已在提交时计算，这里只需将冻结金额扣除即可
            // 注意：实际到账应该在实际转出后设置为COMPLETED，这里只是审核通过
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("message", "审核通过");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> resp = new HashMap<>();
            resp.put("error", "审核失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 审核拒绝
     */
    @PostMapping("/{id}/reject")
    @Transactional
    public ResponseEntity<?> rejectWithdraw(
            @PathVariable Long id, 
            @RequestBody RejectRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            // 检查是否是代理，如果是代理需要检查权限
            Long agentId = extractAgentId(authHeader);
            if (agentId != null) {
                // 检查代理是否有提现审核权限
                Long menuId = getMenuIdByCode("withdraw_review");
                if (menuId == null) {
                    menuId = getMenuIdByCode("withdraw-review");
                }
                if (menuId != null && !agentActionService.hasAction(agentId, menuId, "reject_withdraw")) {
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("error", "代理账号无权进行审核操作");
                    return ResponseEntity.status(403).body(resp);
                }
            }
            Optional<WithdrawRecord> recordOpt = withdrawRecordRepository.findById(id);
            if (!recordOpt.isPresent()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("error", "提现记录不存在");
                return ResponseEntity.badRequest().body(resp);
            }
            
            WithdrawRecord record = recordOpt.get();
            if (!"PENDING".equals(record.getStatus())) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("error", "该记录已处理，无法重复审核");
                return ResponseEntity.badRequest().body(resp);
            }
            
            // 退回冻结的金额
            AssetAccount fundAccount = assetAccountRepository.findByUserIdAndCoin(record.getUserId(), "FUND")
                    .orElse(null);
            if (fundAccount != null) {
                BigDecimal totalAmount = record.getAmount().add(record.getFee());
                fundAccount.setFrozen(fundAccount.getFrozen().subtract(totalAmount));
                fundAccount.setAvailable(fundAccount.getAvailable().add(totalAmount));
                assetAccountRepository.save(fundAccount);
            }
            
            // 更新记录状态
            record.setStatus("REJECTED");
            record.setReviewRemark(req.getRemark());
            record.setReviewedAt(LocalDateTime.now());
            withdrawRecordRepository.save(record);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("message", "已拒绝");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> resp = new HashMap<>();
            resp.put("error", "拒绝失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 标记为已完成（实际转账完成）
     */
    @PostMapping("/{id}/complete")
    @Transactional
    public ResponseEntity<?> completeWithdraw(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            // 检查是否是代理，如果是代理需要检查权限
            Long agentId = extractAgentId(authHeader);
            if (agentId != null) {
                // 检查代理是否有提现审核权限
                Long menuId = getMenuIdByCode("withdraw_review");
                if (menuId == null) {
                    menuId = getMenuIdByCode("withdraw-review");
                }
                if (menuId != null && !agentActionService.hasAction(agentId, menuId, "complete_withdraw")) {
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("error", "代理账号无权进行审核操作");
                    return ResponseEntity.status(403).body(resp);
                }
            }
            Optional<WithdrawRecord> recordOpt = withdrawRecordRepository.findById(id);
            if (!recordOpt.isPresent()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("error", "提现记录不存在");
                return ResponseEntity.badRequest().body(resp);
            }
            
            WithdrawRecord record = recordOpt.get();
            if (!"APPROVED".equals(record.getStatus())) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("error", "只能完成已审核通过的提现记录");
                return ResponseEntity.badRequest().body(resp);
            }
            
            // 扣除冻结金额（实际转出）
            AssetAccount fundAccount = assetAccountRepository.findByUserIdAndCoin(record.getUserId(), "FUND")
                    .orElse(null);
            if (fundAccount != null) {
                BigDecimal totalAmount = record.getAmount().add(record.getFee());
                fundAccount.setFrozen(fundAccount.getFrozen().subtract(totalAmount));
                assetAccountRepository.save(fundAccount);
            }
            
            // 更新记录状态
            record.setStatus("COMPLETED");
            withdrawRecordRepository.save(record);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("message", "标记为已完成");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> resp = new HashMap<>();
            resp.put("error", "操作失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    @Data
    public static class ApproveRequest {
        private String remark;
    }
    
    @Data
    public static class RejectRequest {
        private String remark;
    }
    
    /**
     * 通过菜单代码获取菜单ID
     */
    private Long getMenuIdByCode(String menuCode) {
        return adminMenuRepository.findByMenuCode(menuCode)
                .map(menu -> menu.getId())
                .orElse(null);
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
}

