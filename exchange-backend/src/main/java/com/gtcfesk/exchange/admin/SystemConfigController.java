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

    @GetMapping("/list")
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
        Map<String, String> result = new HashMap<>();
        result.put("message", "配置保存成功");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/saveBatch")
    public ResponseEntity<?> saveBatchConfig(@RequestBody List<Map<String, String>> configs) {
        for (Map<String, String> cfg : configs) {
            systemConfigService.saveConfig(
                cfg.get("key"), 
                cfg.get("value"), 
                cfg.get("description")
            );
        }
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





