package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.WithdrawRecord;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.UserBankCardRepository;
import com.gtcfesk.exchange.repository.UserDigitalAddressRepository;
import com.gtcfesk.exchange.repository.WithdrawRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/withdraw")
@RequiredArgsConstructor
public class WithdrawController {
    
    private final WithdrawRecordRepository withdrawRecordRepository;
    private final AssetAccountRepository assetAccountRepository;
    private final UserDigitalAddressRepository userDigitalAddressRepository;
    private final UserBankCardRepository userBankCardRepository;

    private final FiatCurrencyService fiatCurrencyService;

    /**
     * 提交提现申请
     */
    @PostMapping("/submit")
    @Transactional
    public ResponseEntity<?> submitWithdraw(Authentication auth, @RequestBody Map<String, Object> req) {
        try {
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录");
                return ResponseEntity.status(401).body(resp);
            }
            
            Long userId = Long.parseLong(auth.getName());
            String type = (String) req.get("type"); // digital 或 bank
            if (!"digital".equals(type) && !"bank".equals(type)) {
                throw new com.gtcfesk.exchange.common.BusinessException("提现类型无效");
            }
            String network = (String) req.get("network"); // 如 USDT-TRC20, USD
            BigDecimal originalAmount = new BigDecimal(req.get("amount").toString());
            com.gtcfesk.exchange.common.TradeValidation.positive(originalAmount, "提现金额");
            String currency = "bank".equals(type) ? fiatCurrencyService.currency((String) req.get("currency")) : "USD";
            BigDecimal rate = "bank".equals(type) ? fiatCurrencyService.rate(currency) : BigDecimal.ONE;
            BigDecimal amount = fiatCurrencyService.toUsd(originalAmount, rate);
            String address = (String) req.get("address");
            String remark = (String) req.get("remark");
            
            // 验证参数
            if (type == null || network == null || amount == null || address == null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "参数不完整");
                return ResponseEntity.badRequest().body(resp);
            }
            
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "提现金额必须大于0");
                return ResponseEntity.badRequest().body(resp);
            }
            
            // 获取用户资金账户余额
            AssetAccount fundAccount = assetAccountRepository.findByUserIdAndCoin(userId, "FUND")
                    .orElse(null);
            
            if (fundAccount == null || fundAccount.getAvailable() == null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "资金账户不存在或余额不足");
                return ResponseEntity.badRequest().body(resp);
            }
            
            BigDecimal available = fundAccount.getAvailable();
            BigDecimal fee = calculateFee(type, network, amount); // 计算手续费
            BigDecimal totalNeeded = amount.add(fee); // 提现金额 + 手续费
            
            // 检查余额是否足够
            if (available.compareTo(totalNeeded) < 0) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "余额不足，需要 " + totalNeeded + "，当前可用余额 " + available);
                return ResponseEntity.badRequest().body(resp);
            }
            
            // 验证地址/账户是否属于当前用户
            if ("digital".equals(type)) {
                boolean addressExists = userDigitalAddressRepository.findByUserId(userId).stream()
                        .anyMatch(addr -> address.equals(addr.getAddress()) && network.equals(addr.getNetwork()));
                if (!addressExists) {
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("success", false);
                    resp.put("message", "提币地址未绑定或不属于当前用户");
                    return ResponseEntity.badRequest().body(resp);
                }
            } else if ("bank".equals(type)) {
                boolean accountExists = userBankCardRepository.findByUserId(userId).stream()
                        .anyMatch(card -> address.equals(card.getRecipientAccount()));
                if (!accountExists) {
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("success", false);
                    resp.put("message", "收款账户未绑定或不属于当前用户");
                    return ResponseEntity.badRequest().body(resp);
                }
            }
            
            // 冻结金额（提现金额 + 手续费）
            fundAccount.setAvailable(available.subtract(totalNeeded));
            fundAccount.setFrozen(fundAccount.getFrozen().add(totalNeeded));
            assetAccountRepository.save(fundAccount);
            
            // 创建提现记录
            WithdrawRecord record = new WithdrawRecord();
            record.setUserId(userId);
            record.setType(type);
            record.setNetwork(network);
            record.setAmount(amount);
            record.setFee(fee);
            record.setActualAmount(amount); // 银行卡以 USD 结算
            if ("bank".equals(type)) {
                record.setCurrency(currency);
                record.setOriginalAmount(originalAmount);
                record.setExchangeRate(rate);
            }
            record.setAddress(address);
            record.setRemark(remark);
            record.setStatus("PENDING");
            
            withdrawRecordRepository.save(record);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "提现申请已提交，等待审核");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            e.printStackTrace();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "提交失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 获取提现记录
     */
    @GetMapping("/records")
    public ResponseEntity<?> getRecords(Authentication auth) {
        try {
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录");
                return ResponseEntity.status(401).body(resp);
            }
            
            Long userId = Long.parseLong(auth.getName());
            List<WithdrawRecord> records = withdrawRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", records);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 计算手续费
     */
    private BigDecimal calculateFee(String type, String network, BigDecimal amount) {
        // 简化处理：数字货币手续费为0，银行卡手续费为0
        // 后续可以从系统配置中读取手续费率
        return BigDecimal.ZERO;
    }

    /**
     * 计算 USD 结算金额和手续费
     */
    @PostMapping("/calculate")
    public ResponseEntity<?> calculateAmount(@RequestBody Map<String, Object> req) {
        try {
            String type = (String) req.get("type");
            if (!"digital".equals(type) && !"bank".equals(type)) {
                throw new com.gtcfesk.exchange.common.BusinessException("提现类型无效");
            }
            String network = (String) req.get("network");
            BigDecimal originalAmount = new BigDecimal(req.get("amount").toString());
            com.gtcfesk.exchange.common.TradeValidation.positive(originalAmount, "提现金额");
            String currency = "bank".equals(type) ? fiatCurrencyService.currency((String) req.get("currency")) : "USD";
            BigDecimal rate = "bank".equals(type) ? fiatCurrencyService.rate(currency) : BigDecimal.ONE;
            BigDecimal amount = fiatCurrencyService.toUsd(originalAmount, rate);

            BigDecimal fee = calculateFee(type, network, amount);
            BigDecimal actualAmount = amount;

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("fee", fee);
            resp.put("actualAmount", actualAmount);
            resp.put("amount", amount);
            resp.put("totalNeeded", amount.add(fee));
            resp.put("settlementCurrency", "USD");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "计算失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

}
