package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.DepositRecord;
import com.gtcfesk.exchange.entity.DepositSetting;
import com.gtcfesk.exchange.repository.DepositRecordRepository;
import com.gtcfesk.exchange.repository.DepositSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/deposit")
@RequiredArgsConstructor
public class DepositController {

    private final DepositSettingRepository depositSettingRepository;
    private final DepositRecordRepository depositRecordRepository;
    private final FiatCurrencyService fiatCurrencyService;

    @GetMapping("/settings/list")
    public ResponseEntity<?> getSettingsList(@RequestParam(required = false) String type) {
        try {
            List<DepositSetting> enabledSettings;
            if (type != null && !type.isEmpty()) {
                // 按类型筛选
                enabledSettings = depositSettingRepository.findByTypeAndEnabled(type, true);
            } else {
                // 获取所有启用的充值设置
                List<DepositSetting> settings = depositSettingRepository.findAll();
                enabledSettings = settings.stream()
                        .filter(DepositSetting::getEnabled)
                        .collect(java.util.stream.Collectors.toList());
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", enabledSettings);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @GetMapping("/settings/bank")
    public ResponseEntity<?> getBankSettings() {
        try {
            // 获取启用的银行卡设置
            List<DepositSetting> bankSettings = depositSettingRepository.findByTypeAndEnabled("bank", true);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            if (!bankSettings.isEmpty()) {
                DepositSetting setting = bankSettings.get(0);
                resp.put("hasBank", true);
                resp.put("bankName", setting.getBankName());
                resp.put("bankAccount", setting.getBankAccount());
                resp.put("accountName", setting.getAccountName());
            } else {
                resp.put("hasBank", false);
            }
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(@RequestParam(required = false) String network) {
        try {
            DepositSetting setting = null;
            if (network != null && !network.isEmpty()) {
                setting = depositSettingRepository.findByNetwork(network).orElse(null);
            } else {
                // 返回第一个启用的设置
                List<DepositSetting> settings = depositSettingRepository.findAll();
                setting = settings.stream()
                        .filter(DepositSetting::getEnabled)
                        .findFirst()
                        .orElse(null);
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            if (setting != null) {
                resp.put("address", setting.getAddress());
                resp.put("qrCode", setting.getQrCode());
            } else {
                resp.put("address", "");
                resp.put("qrCode", "");
            }
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submitDeposit(
            Authentication auth,
            @RequestBody Map<String, Object> req) {
        try {
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录");
                return ResponseEntity.status(401).body(resp);
            }

            Long userId = Long.parseLong(auth.getName());
            String type = (String) req.get("type");
            String network = (String) req.get("network");
            BigDecimal amount = new BigDecimal(req.get("amount").toString());
            com.gtcfesk.exchange.common.TradeValidation.positive(amount, "充值金额");
            String address = (String) req.get("address");
            String proofImage = (String) req.get("proofImage");

            if (type == null || network == null || amount == null || address == null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "参数不完整");
                return ResponseEntity.badRequest().body(resp);
            }

            DepositRecord record = new DepositRecord();
            record.setUserId(userId);
            record.setType(type);
            record.setNetwork(network);
            String currency = fiatCurrencyService.currency((String) req.get("currency"));
            BigDecimal rate = fiatCurrencyService.rate(currency);
            record.setCurrency(currency);
            record.setOriginalAmount(amount);
            record.setExchangeRate(rate);
            record.setAmount(fiatCurrencyService.toUsd(amount, rate));
            record.setAddress(address);
            record.setProofImage(proofImage);
            record.setStatus("PENDING");

            depositRecordRepository.save(record);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "充值申请已提交");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "提交失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

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
            List<DepositRecord> records = depositRecordRepository.findByUserIdOrderByCreatedAtDesc(userId);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", records);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
}

