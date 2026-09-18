package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.DepositSetting;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/deposit/settings")
@RequiredArgsConstructor
public class DepositSettingController {

    private final DepositSettingService depositSettingService;

    @GetMapping
    public ResponseEntity<?> getAllSettings() {
        try {
            List<DepositSetting> settings = depositSettingService.getAllSettings();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", settings);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @PostMapping
    public ResponseEntity<?> createSetting(@RequestBody DepositSetting setting) {
        try {
            DepositSetting created = depositSettingService.createSetting(setting);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", created);
            resp.put("message", "添加成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "添加失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateSetting(@PathVariable Long id, @RequestBody DepositSetting setting) {
        try {
            DepositSetting updated = depositSettingService.updateSetting(id, setting);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", updated);
            resp.put("message", "更新成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "更新失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSetting(@PathVariable Long id) {
        try {
            depositSettingService.deleteSetting(id);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "删除成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
}



