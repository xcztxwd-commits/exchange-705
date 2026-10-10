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
    private void auditControl(String action,String object,String detail,String reason){if(com.gtcfesk.exchange.control.ControlIdentity.isAccess())controlAudit.recordCurrent(action,object,detail,reason); }
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService controlAudit;
    @Autowired
    private SystemConfigService systemConfigService;
    @Autowired
    private com.gtcfesk.exchange.market.ForexQuoteMarketService market;

    private void refreshMarketAfterCommit() {
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

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/save")
    public ResponseEntity<?> saveConfig(@RequestBody Map<String, String> req) {
        String key = req.get("key");
        String value = req.get("value");
        String description = req.get("description");
        systemConfigService.saveConfig(key, value, description);
        auditControl("CONFIG_UPDATE",key,"value changed (redacted)",req.get("reason"));
        if ("market.conversion.currencies".equals(key) || com.gtcfesk.exchange.market.YahooQuoteStream.ENABLED_KEY.equals(key)) refreshMarketAfterCommit();
        Map<String, String> result = new HashMap<>();
        result.put("message", "配置保存成功");
        return ResponseEntity.ok(result);
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/saveBatch")
    public ResponseEntity<?> saveBatchConfig(@RequestBody List<Map<String, String>> configs) {
        boolean marketChanged = false;
        for (Map<String, String> cfg : configs) {
            systemConfigService.saveConfig(
                cfg.get("key"), 
                cfg.get("value"), 
                cfg.get("description")
            );
            auditControl("CONFIG_UPDATE",cfg.get("key"),"value changed (redacted)",cfg.get("reason"));
            marketChanged |= "market.conversion.currencies".equals(cfg.get("key")) || com.gtcfesk.exchange.market.YahooQuoteStream.ENABLED_KEY.equals(cfg.get("key"));
        }
        if (marketChanged) refreshMarketAfterCommit();
        Map<String, String> result = new HashMap<>();
        result.put("message", "批量保存成功");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/get")
    public ResponseEntity<?> getConfig(@RequestParam String key) {
        String value = com.gtcfesk.exchange.tenant.TenantSecrets.secret(key) ? com.gtcfesk.exchange.tenant.TenantSecrets.MASK : systemConfigService.getConfigValue(key);
        Map<String, String> result = new HashMap<>();
        result.put("key", key);
        result.put("value", value);
        if ("share.templates".equals(key)) result.put("brand", systemConfigService.getConfigValue("site.name"));
        return ResponseEntity.ok(result);
    }
}





