package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.DepositRecord;
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
@RequestMapping("/api/admin/deposit/review")
@RequiredArgsConstructor
public class DepositReviewController {

    private final DepositReviewService depositReviewService;
    private final JwtUtil jwtUtil;
    private final UserAccountRepository userAccountRepository;

    @GetMapping("/list")
    public ResponseEntity<?> getDepositRecords(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) Long filterAgentId, // 管理员筛选代理ID
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            Long agentId = extractAgentId(authHeader);
            // 如果是管理员传入了筛选代理ID，使用筛选的代理ID；否则使用登录的代理ID
            Long targetAgentId = (agentId == null && filterAgentId != null) ? filterAgentId : agentId;
            List<DepositRecord> records = depositReviewService.getDepositRecords(status, userId, userEmail, targetAgentId);
            
            // 为每条记录添加用户备注
            List<Map<String, Object>> resultList = records.stream().map(record -> {
                Map<String, Object> recordMap = new HashMap<>();
                recordMap.put("id", record.getId());
                recordMap.put("userId", record.getUserId());
                recordMap.put("type", record.getType());
                recordMap.put("network", record.getNetwork());
                recordMap.put("amount", record.getAmount());
                recordMap.put("address", record.getAddress());
                recordMap.put("status", record.getStatus());
                recordMap.put("proofImage", record.getProofImage());
                recordMap.put("remark", record.getRemark());
                recordMap.put("createdAt", record.getCreatedAt());
                recordMap.put("agentInfo", record.getAgentInfo());
                recordMap.put("currency",record.getCurrency());recordMap.put("originalAmount",com.gtcfesk.exchange.user.DepositOrderService.decimal(record.getOriginalAmount()));
                recordMap.put("exchangeRate",com.gtcfesk.exchange.user.DepositOrderService.decimal(record.getExchangeRate()));
                recordMap.put("source",record.getSource()==null?"LEGACY_UNKNOWN":record.getSource());recordMap.put("orderNo",record.getOrderNo());
                recordMap.put("reviewRemark",record.getReviewRemark());recordMap.put("reviewedAt",record.getReviewedAt());recordMap.put("creditedAt",record.getCreditedAt());
                recordMap.put("reviewedByName",record.getReviewedByName());recordMap.put("accountType",record.getAccountType());
                
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
        } catch (org.springframework.web.server.ResponseStatusException | org.springframework.security.access.AccessDeniedException e) { throw e;
        } catch (org.springframework.dao.OptimisticLockingFailureException | org.springframework.dao.PessimisticLockingFailureException e) { throw e;
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @PostMapping("/approve/{id}")
    public ResponseEntity<?> approveDeposit(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            depositReviewService.approveDeposit(id);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "审核通过，已充值到用户账户");
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.server.ResponseStatusException | org.springframework.security.access.AccessDeniedException e) { throw e;
        } catch (org.springframework.dao.OptimisticLockingFailureException | org.springframework.dao.PessimisticLockingFailureException e) { throw e;
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "审核失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @PostMapping("/reject/{id}")
    public ResponseEntity<?> rejectDeposit(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            String remark = req != null ? req.get("remark") : null;
            depositReviewService.rejectDeposit(id, remark);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "已拒绝该充值申请");
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.server.ResponseStatusException | org.springframework.security.access.AccessDeniedException e) { throw e;
        } catch (org.springframework.dao.OptimisticLockingFailureException | org.springframework.dao.PessimisticLockingFailureException e) { throw e;
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "操作失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 从请求头中提取代理ID
     */
    private Long extractAgentId(String ignored) { return com.gtcfesk.exchange.config.BackendAccess.agentId(); }
}
