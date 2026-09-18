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
    
    private final AssetAccountRepository assetAccountRepository;
    private final TransferRecordRepository transferRecordRepository;
    
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
        if (req.getFromAccount().equals(req.getToAccount())) {
            throw new BusinessException("转出账户和转入账户不能相同");
        }
        if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("划转金额必须大于0");
        }
        
        // 验证账户类型
        String fromAccount = req.getFromAccount().toUpperCase();
        String toAccount = req.getToAccount().toUpperCase();
        if (!isValidAccountType(fromAccount) || !isValidAccountType(toAccount)) {
            throw new BusinessException("账户类型无效");
        }
        
        // 获取转出账户
        AssetAccount fromAsset = assetAccountRepository
                .findByUserIdAndCoin(userId, fromAccount)
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
        AssetAccount toAsset = assetAccountRepository
                .findByUserIdAndCoin(userId, toAccount)
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
        
        assetAccountRepository.save(fromAsset);
        assetAccountRepository.save(toAsset);
        
        // 创建划转记录
        TransferRecord record = new TransferRecord();
        record.setUserId(userId);
        record.setFromAccount(fromAccount);
        record.setToAccount(toAccount);
        record.setAmount(req.getAmount());
        transferRecordRepository.save(record);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "划转成功");
        return ResponseEntity.ok(result);
    }
    
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
        
        Page<TransferRecord> records = transferRecordRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        
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
        private BigDecimal amount;
    }
}



