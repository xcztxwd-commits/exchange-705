package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.KycRecord;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.KycRecordRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/api/admin/kyc")
@RequiredArgsConstructor
public class KycReviewController {
    
    private final KycRecordRepository kycRecordRepository;
    private final UserAccountRepository userAccountRepository;
    private final JwtUtil jwtUtil;
    
    // 获取实名认证申请列表
    @GetMapping("/list")
    public ResponseEntity<?> getKycList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) Long filterAgentId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        
        Long agentId = extractAgentId(authHeader);
        // 如果是管理员传入了筛选代理ID，使用筛选的代理ID；否则使用登录的代理ID
        Long targetAgentId = (agentId == null && filterAgentId != null) ? filterAgentId : agentId;
        
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        Pageable pageable = PageRequest.of(page, size, sort);
        
        Page<KycRecord> records;
        if (status != null && !status.isEmpty()) {
            records = kycRecordRepository.findByStatus(status, pageable);
        } else {
            records = kycRecordRepository.findAll(pageable);
        }
        
        // 如果指定了代理ID（代理登录或管理员筛选），只返回该代理下级用户的记录
        List<KycRecord> filteredRecords = records.getContent();
        if (targetAgentId != null) {
            List<UserAccount> subordinates = userAccountRepository.findByParentUserId(targetAgentId);
            Set<Long> subordinateUserIds = subordinates.stream()
                    .map(UserAccount::getId)
                    .collect(Collectors.toSet());
            
            if (!subordinateUserIds.isEmpty()) {
                filteredRecords = filteredRecords.stream()
                        .filter(record -> subordinateUserIds.contains(record.getUserId()))
                        .collect(Collectors.toList());
            } else {
                filteredRecords = new java.util.ArrayList<>();
            }
        }
        
        // 按用户ID过滤
        if (userId != null) {
            filteredRecords = filteredRecords.stream()
                    .filter(record -> record.getUserId() != null && record.getUserId().equals(userId))
                    .collect(Collectors.toList());
        }
        
        // 按用户邮箱过滤
        if (userEmail != null && !userEmail.trim().isEmpty()) {
            Optional<UserAccount> userOpt = userAccountRepository.findByEmail(userEmail.trim());
            if (userOpt.isPresent()) {
                Long targetUserId = userOpt.get().getId();
                filteredRecords = filteredRecords.stream()
                        .filter(record -> record.getUserId() != null && record.getUserId().equals(targetUserId))
                        .collect(Collectors.toList());
            } else {
                // 用户不存在，返回空列表
                filteredRecords = new java.util.ArrayList<>();
            }
        }
        
        // 填充代理信息
        List<Map<String, Object>> resultList = filteredRecords.stream().map(record -> {
            Map<String, Object> recordMap = new HashMap<>();
            recordMap.put("id", record.getId());
            recordMap.put("userId", record.getUserId());
            recordMap.put("realName", record.getRealName());
            recordMap.put("idNumber", record.getIdNumber());
            recordMap.put("idFrontImage", record.getIdFrontImage());
            recordMap.put("idBackImage", record.getIdBackImage());
            recordMap.put("status", record.getStatus());
            recordMap.put("reviewRemark", record.getReviewRemark());
            recordMap.put("createdAt", record.getCreatedAt());
            recordMap.put("reviewedAt", record.getReviewedAt());
            
            // 填充代理信息和用户备注
            UserAccount user = userAccountRepository.findById(record.getUserId()).orElse(null);
            if (user != null) {
                // 添加用户备注
                recordMap.put("userRemark", user.getRemark());
                
                // 填充代理信息
                if (user.getParentUserId() != null) {
                    UserAccount agent = userAccountRepository.findById(user.getParentUserId()).orElse(null);
                    if (agent != null) {
                        String agentName = (agent.getNickname() != null && !agent.getNickname().isEmpty()) 
                                ? agent.getNickname() : agent.getEmail();
                        recordMap.put("agentInfo", "所属代理:" + agentName);
                    } else {
                        recordMap.put("agentInfo", null);
                    }
                } else {
                    recordMap.put("agentInfo", null);
                }
            } else {
                recordMap.put("agentInfo", null);
                recordMap.put("userRemark", null);
            }
            
            return recordMap;
        }).collect(Collectors.toList());
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", resultList);
        result.put("total", targetAgentId != null ? filteredRecords.size() : records.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(result);
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
    
    // 获取详情
    @GetMapping("/{id}")
    public ResponseEntity<?> getKycDetail(@PathVariable Long id) {
        KycRecord record = kycRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException("申请不存在"));
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("record", record);
        return ResponseEntity.ok(result);
    }
    
    // 审核通过
    @PostMapping("/{id}/approve")
    @Transactional
    public ResponseEntity<?> approveKyc(
            Authentication auth,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> req) {
        
        KycRecord record = kycRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException("申请不存在"));
        
        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException("该申请已处理");
        }
        
        // 获取审核人ID
        Long reviewerId = null;
        if (auth != null && auth.getName() != null) {
            try {
                reviewerId = Long.parseLong(auth.getName());
            } catch (Exception e) {
                // 忽略
            }
        }
        
        // 更新申请记录
        record.setStatus("APPROVED");
        record.setReviewedBy(reviewerId);
        record.setReviewedAt(LocalDateTime.now());
        if (req != null && req.containsKey("remark")) {
            record.setReviewRemark(req.get("remark"));
        }
        kycRecordRepository.save(record);
        
        // 更新用户实名状态
        UserAccount user = userAccountRepository.findById(record.getUserId())
                .orElseThrow(() -> new BusinessException("用户不存在"));
        user.setKycStatus("VERIFIED");
        user.setKycLevel(1);
        userAccountRepository.save(user);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "审核通过");
        return ResponseEntity.ok(result);
    }
    
    // 审核拒绝
    @PostMapping("/{id}/reject")
    @Transactional
    public ResponseEntity<?> rejectKyc(
            Authentication auth,
            @PathVariable Long id,
            @RequestBody Map<String, String> req) {
        
        KycRecord record = kycRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException("申请不存在"));
        
        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException("该申请已处理");
        }
        
        String remark = req != null ? req.get("remark") : null;
        if (remark == null || remark.trim().isEmpty()) {
            throw new BusinessException("拒绝原因不能为空");
        }
        
        // 获取审核人ID
        Long reviewerId = null;
        if (auth != null && auth.getName() != null) {
            try {
                reviewerId = Long.parseLong(auth.getName());
            } catch (Exception e) {
                // 忽略
            }
        }
        
        // 更新申请记录
        record.setStatus("REJECTED");
        record.setReviewedBy(reviewerId);
        record.setReviewedAt(LocalDateTime.now());
        record.setReviewRemark(remark);
        kycRecordRepository.save(record);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已拒绝");
        return ResponseEntity.ok(result);
    }
}

