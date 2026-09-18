package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.LoanSetting;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/loan/settings")
@RequiredArgsConstructor
public class LoanSettingController {

    private final LoanSettingService loanSettingService;

    @GetMapping
    public ResponseEntity<?> getAllSettings() {
        try {
            List<LoanSetting> settings = loanSettingService.getAllSettings();
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
    public ResponseEntity<?> createSetting(@RequestBody LoanSetting setting) {
        try {
            LoanSetting created = loanSettingService.createSetting(setting);
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
    public ResponseEntity<?> updateSetting(@PathVariable Long id, @RequestBody LoanSetting setting) {
        try {
            LoanSetting updated = loanSettingService.updateSetting(id, setting);
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
            loanSettingService.deleteSetting(id);
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



