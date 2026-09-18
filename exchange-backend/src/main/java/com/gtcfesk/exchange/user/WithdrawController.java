package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.WithdrawRecord;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.UserBankCardRepository;
import com.gtcfesk.exchange.repository.UserDigitalAddressRepository;
import com.gtcfesk.exchange.repository.WithdrawRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${forex.api.base-url:https://apiforex.cn/api/v1}")
    private String forexBaseUrl;

    @Value("${forex.api.key:}")
    private String forexApiKey;
    
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
            String network = (String) req.get("network"); // 如 USDT-TRC20, USD
            BigDecimal amount = new BigDecimal(req.get("amount").toString());
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
            // 实际到账金额：
            // - 数字货币：等于提现金额
            // - 银行卡：按 USD -> 目标货币汇率换算
            BigDecimal actualAmount = calculateActualAmount(type, network, amount);
            record.setActualAmount(actualAmount);
            record.setAddress(address);
            record.setRemark(remark);
            record.setStatus("PENDING");
            
            withdrawRecordRepository.save(record);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "提现申请已提交，等待审核");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "提交失败: " + e.getMessage());
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
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
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
     * 计算预计到账金额（考虑银行卡提现时的汇率转换）
     */
    @PostMapping("/calculate")
    public ResponseEntity<?> calculateAmount(@RequestBody Map<String, Object> req) {
        try {
            String type = (String) req.get("type");
            String network = (String) req.get("network");
            BigDecimal amount = new BigDecimal(req.get("amount").toString());

            BigDecimal fee = calculateFee(type, network, amount);
            BigDecimal actualAmount = calculateActualAmount(type, network, amount);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("fee", fee);
            resp.put("actualAmount", actualAmount);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "计算失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 计算实际到账金额：
     * - 数字货币：直接返回 amount
     * - 银行卡：按 USD -> 目标货币（network，例如 EUR）汇率转换
     */
    private BigDecimal calculateActualAmount(String type, String network, BigDecimal amount) {
        if (!"bank".equals(type) || network == null || network.trim().isEmpty()) {
            return amount;
        }
        try {
            BigDecimal rate = getForexRate("USD", network.trim());
            return amount.multiply(rate).setScale(2, RoundingMode.DOWN);
        } catch (Exception e) {
            // 汇率获取失败时，回退为 1:1，避免影响提现流程
            e.printStackTrace();
            return amount;
        }
    }

    /**
     * 从 Forex API 获取汇率
     * base: 基础币种（本项目资金账户默认 USD）
     * target: 目标币种（如 EUR）
     */
    private BigDecimal getForexRate(String base, String target) throws Exception {
        // 如果没有配置 API Key，则使用 1:1 汇率，保证接口可用
        if (forexApiKey == null || forexApiKey.isEmpty()) {
            return BigDecimal.ONE;
        }

        String encodedBase = URLEncoder.encode(base, StandardCharsets.UTF_8.name());
        String encodedTarget = URLEncoder.encode(target, StandardCharsets.UTF_8.name());

        String url = forexBaseUrl + "/latest?base=" + encodedBase + "&symbols=" + encodedTarget
                + "&apikey=" + URLEncoder.encode(forexApiKey, StandardCharsets.UTF_8.name());

        String json = restTemplate.getForObject(url, String.class);
        JsonNode root = objectMapper.readTree(json);

        boolean success = root.path("success").asBoolean(false);
        if (!success) {
            throw new IllegalStateException("Forex API 调用失败: " + root.path("error").asText("unknown error"));
        }

        JsonNode ratesNode = root.path("data").path("rates");
        if (!ratesNode.has(target)) {
            throw new IllegalStateException("Forex API 响应中缺少汇率: " + target);
        }

        String rateStr = ratesNode.get(target).asText();
        return new BigDecimal(rateStr);
    }
}



