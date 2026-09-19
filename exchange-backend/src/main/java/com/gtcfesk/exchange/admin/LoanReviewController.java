package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.LoanRecord;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/loan/review")
@RequiredArgsConstructor
public class LoanReviewController {

    private final LoanReviewService loanReviewService;
    private final JwtUtil jwtUtil;
    private final UserAccountRepository userAccountRepository;

    @GetMapping("/list")
    public ResponseEntity<?> getLoanRecords(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) Long filterAgentId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            Long agentId = extractAgentId(authHeader);
            // 如果是管理员传入了筛选代理ID，使用筛选的代理ID；否则使用登录的代理ID
            Long targetAgentId = (agentId == null && filterAgentId != null) ? filterAgentId : agentId;
            
            List<LoanRecord> records = loanReviewService.getLoanRecords(status, userId, userEmail, targetAgentId);
            
            // 为每条记录添加用户备注
            List<Map<String, Object>> resultList = records.stream().map(record -> {
                Map<String, Object> recordMap = new HashMap<>();
                recordMap.put("id", record.getId());
                recordMap.put("userId", record.getUserId());
                recordMap.put("realName", record.getRealName());
                recordMap.put("agentInfo", record.getAgentInfo());
                recordMap.put("phone", record.getPhone());
                recordMap.put("address", record.getAddress());
                recordMap.put("amount", record.getAmount());
                recordMap.put("days", record.getDays());
                recordMap.put("dailyRate", record.getDailyRate());
                recordMap.put("freeDays", record.getFreeDays());
                recordMap.put("totalInterest", record.getTotalInterest());
                recordMap.put("repaymentAmount", record.getRepaymentAmount());
                recordMap.put("status", record.getStatus());
                recordMap.put("contractSigned", record.getContractSigned());
                recordMap.put("createdAt", record.getCreatedAt());
                recordMap.put("approvedAt", record.getApprovedAt());
                recordMap.put("repaymentDate", record.getRepaymentDate());
                recordMap.put("remark", record.getRemark());
                
                // 添加用户备注
                if (record.getUserId() != null) {
                    UserAccount user = userAccountRepository.findById(record.getUserId()).orElse(null);
                    if (user != null) {
                        recordMap.put("userRemark", user.getRemark());
                    }
                }
                
                return recordMap;
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
    public ResponseEntity<?> approveLoan(@PathVariable Long id) {
        try {
            loanReviewService.approveLoan(id);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "审核通过");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "审核失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @PostMapping("/reject/{id}")
    public ResponseEntity<?> rejectLoan(@PathVariable Long id, @RequestBody Map<String, String> request) {
        try {
            String remark = request.getOrDefault("remark", "");
            loanReviewService.rejectLoan(id, remark);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "审核拒绝");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "操作失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
}



