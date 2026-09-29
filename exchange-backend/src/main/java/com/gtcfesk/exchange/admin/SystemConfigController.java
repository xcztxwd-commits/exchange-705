package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.SystemConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/config")
public class SystemConfigController {
    @Autowired
    private SystemConfigService systemConfigService;
    @Autowired
    private com.gtcfesk.exchange.market.ForexQuoteMarketService market;

    private void refreshCurrenciesAfterCommit() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { market.requestSymbolRefresh(); }
                });
        } else market.requestSymbolRefresh();
    }

    @GetMapping("/list")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "settings", action = "")
    public ResponseEntity<?> getAllConfigs() {
        List<SystemConfig> configs = systemConfigService.getAllConfigs();
        return ResponseEntity.ok(configs);
    }

    @PostMapping("/save")
    public ResponseEntity<?> saveConfig(@RequestBody Map<String, String> req) {
        String key = req.get("key");
        String value = req.get("value");
        String description = req.get("description");
        systemConfigService.saveConfig(key, value, description);
        if ("market.conversion.currencies".equals(key)) refreshCurrenciesAfterCommit();
        Map<String, String> result = new HashMap<>();
        result.put("message", "配置保存成功");
        return ResponseEntity.ok(result);
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/saveBatch")
    public ResponseEntity<?> saveBatchConfig(@RequestBody List<Map<String, String>> configs) {
        boolean currenciesChanged = false;
        for (Map<String, String> cfg : configs) {
            systemConfigService.saveConfig(
                cfg.get("key"), 
                cfg.get("value"), 
                cfg.get("description")
            );
            currenciesChanged |= "market.conversion.currencies".equals(cfg.get("key"));
        }
        if (currenciesChanged) refreshCurrenciesAfterCommit();
        Map<String, String> result = new HashMap<>();
        result.put("message", "批量保存成功");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/get")
    public ResponseEntity<?> getConfig(@RequestParam String key) {
        String value = systemConfigService.getConfigValue(key);
        Map<String, String> result = new HashMap<>();
        result.put("key", key);
        result.put("value", value);
        return ResponseEntity.ok(result);
    }
}





