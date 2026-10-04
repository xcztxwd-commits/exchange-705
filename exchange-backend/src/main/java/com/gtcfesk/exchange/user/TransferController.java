package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.TransferRecord;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.TransferRecordRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/transfer")
@RequiredArgsConstructor
public class TransferController {
    
    private final com.gtcfesk.exchange.repository.UserAccountRepository users;
    private final AssetAccountRepository assetAccountRepository;
    private final TransferRecordRepository transferRecordRepository;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager em;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService audit;
    
    /**
     * 划转
     */
    @PostMapping("/submit")
    @Transactional
    public ResponseEntity<?> transfer(
            Authentication auth,
            @RequestBody TransferRequest req) {
        
        if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
            throw new BusinessException("未登录");
        }
        
        Long userId = Long.parseLong(auth.getName());
        
        // 验证参数
        if (req.getFromAccount() == null || req.getFromAccount().isEmpty()) {
            throw new BusinessException("转出账户不能为空");
        }
        if (req.getToAccount() == null || req.getToAccount().isEmpty()) {
            throw new BusinessException("转入账户不能为空");
        }
        if (req.getFromAccount().trim().equalsIgnoreCase(req.getToAccount().trim())) {
            throw new BusinessException("转出账户和转入账户不能相同");
        }
        if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("划转金额必须大于0");
        }
        
        // 验证账户类型
        String fromAccount = req.getFromAccount().trim().toUpperCase(java.util.Locale.ROOT);
        String toAccount = req.getToAccount().trim().toUpperCase(java.util.Locale.ROOT);
        if (!isValidAccountType(fromAccount) || !isValidAccountType(toAccount)) {
            throw new BusinessException("账户类型无效");
        }
        
        com.gtcfesk.exchange.common.TradeValidation.positive(req.getAmount(), "划转金额");
        com.gtcfesk.exchange.common.OrderRequest.required(req.getRequestId());
        // Serialize account creation and idempotency for this user; lock all accounts in fixed order.
        if(em!=null){em.flush();com.gtcfesk.exchange.entity.UserAccount loaded=users.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId).orElseThrow(()->new BusinessException("用户不存在"));em.refresh(loaded,javax.persistence.LockModeType.PESSIMISTIC_WRITE);}
        com.gtcfesk.exchange.entity.UserAccount customer=users.lockById(userId).orElseThrow(() -> new BusinessException("用户不存在"));
        if(em!=null){em.flush();em.refresh(customer,javax.persistence.LockModeType.PESSIMISTIC_WRITE);}
        if(em!=null){em.flush();java.util.List<AssetAccount> loaded=new java.util.ArrayList<>(assetAccountRepository.findByTenantIdAndUserId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),userId));loaded.sort(java.util.Comparator.comparing(AssetAccount::getCoin).thenComparing(AssetAccount::getId));for(AssetAccount row:loaded){em.refresh(row,javax.persistence.LockModeType.PESSIMISTIC_WRITE);com.gtcfesk.exchange.tenant.TenantContext.require(row.getTenantId());if(!userId.equals(row.getUserId()))throw new com.gtcfesk.exchange.common.BusinessException("账户归属已变更，请重试");}}
        java.util.List<AssetAccount> lockedAccounts=assetAccountRepository.lockByUserId(userId);
        if(em!=null){em.flush();for(AssetAccount account:lockedAccounts)em.refresh(account,javax.persistence.LockModeType.PESSIMISTIC_WRITE);}
        if (req.getRequestId() != null) {
            TransferRecord previous = transferRecordRepository.findByTenantIdAndUserIdAndRequestId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, req.getRequestId()).orElse(null);
            if (previous != null) {
                if(em!=null)em.refresh(previous,javax.persistence.LockModeType.PESSIMISTIC_READ);
                if (!fromAccount.equals(previous.getFromAccount()) || !toAccount.equals(previous.getToAccount())
                        || req.getAmount().compareTo(previous.getAmount()) != 0) throw new BusinessException("请求编号已用于不同划转");
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("message", "划转已完成");
                return ResponseEntity.ok(result);
            }
        }
        // 获取转出账户
        AssetAccount fromAsset = lockedAccounts.stream().filter(account->fromAccount.equals(account.getCoin())).findFirst()
                .orElseGet(() -> {
                    AssetAccount a = new AssetAccount();
                    a.setUserId(userId);
                    a.setCoin(fromAccount);
                    a.setAvailable(BigDecimal.ZERO);
                    a.setFrozen(BigDecimal.ZERO);
                    return a;
                });
        
        // 检查余额是否充足
        BigDecimal available = fromAsset.getAvailable() != null ? fromAsset.getAvailable() : BigDecimal.ZERO;
        if (available.compareTo(req.getAmount()) < 0) {
            throw new BusinessException("余额不足");
        }
        
        // 获取转入账户
        AssetAccount toAsset = lockedAccounts.stream().filter(account->toAccount.equals(account.getCoin())).findFirst()
                .orElseGet(() -> {
                    AssetAccount a = new AssetAccount();
                    a.setUserId(userId);
                    a.setCoin(toAccount);
                    a.setAvailable(BigDecimal.ZERO);
                    a.setFrozen(BigDecimal.ZERO);
                    return a;
                });
        
        // 执行划转
        fromAsset.setAvailable(available.subtract(req.getAmount()));
        BigDecimal toAvailable = toAsset.getAvailable() != null ? toAsset.getAvailable() : BigDecimal.ZERO;
        toAsset.setAvailable(toAvailable.add(req.getAmount()));
        
        assetAccountRepository.save(fromAsset);checkpoint("transfer-from-account");
        assetAccountRepository.save(toAsset);checkpoint("transfer-to-account");
        
        // 创建划转记录
        TransferRecord record = new TransferRecord();
        record.setUserId(userId);
        record.setRequestId(req.getRequestId());
        record.setFromAccount(fromAccount);
        record.setToAccount(toAccount);
        record.setAmount(req.getAmount());
        transferRecordRepository.save(record);checkpoint("transfer-receipt");
        auditSuccess("TRANSFER",String.valueOf(record.getId()),"userId="+userId+"; from="+fromAccount+"; to="+toAccount+"; amount="+req.getAmount()+"; fromBefore="+available+"; fromAfter="+fromAsset.getAvailable()+"; toBefore="+toAvailable+"; toAfter="+toAsset.getAvailable(),null);checkpoint("transfer-audit");
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "划转成功");
        return ResponseEntity.ok(result);
    }
    
    private void auditSuccess(String action,String object,String detail,String reason) {
        if(com.gtcfesk.exchange.control.ControlIdentity.current()!=null)audit.recordCurrent(action,object,detail,reason);
        else audit.record(null,com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),null,action,object,"SUCCESS",detail,reason);
    }
    /** Isolated rollback tests may fail after each actual financial write. */
    protected void checkpoint(String stage) { }

    /**
     * 查询划转记录
     */
    @GetMapping("/records")
    public ResponseEntity<?> getRecords(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
            throw new BusinessException("未登录");
        }
        
        Long userId = Long.parseLong(auth.getName());
        
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        Pageable pageable = PageRequest.of(page, size, sort);
        
        Page<TransferRecord> records = transferRecordRepository.findByTenantIdAndUserIdOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, pageable);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", records.getContent());
        result.put("total", records.getTotalElements());
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 验证账户类型是否有效
     */
    private boolean isValidAccountType(String accountType) {
        return "FUND".equals(accountType) || 
               "CONTRACT".equals(accountType) || 
               "OPTION".equals(accountType);
    }
    
    @Data
    public static class TransferRequest {
        private String fromAccount; // FUND, CONTRACT, OPTION
        private String toAccount; // FUND, CONTRACT, OPTION
        private String requestId;
        private BigDecimal amount;
    }
}



