package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class CustomerServiceController {
    
    private final SystemConfigService systemConfigService;
    private final com.gtcfesk.exchange.support.SupportSettings supportSettings;
    
    /**
     * 获取客服链接
     */

    @GetMapping("/customer-service/link")
    public ResponseEntity<?> getCustomerServiceLink() {
        String link = supportSettings.externalLink();
        
        Map<String, Object> result = new HashMap<>();
        result.put("link", link != null ? link : "");
        String mode = supportSettings.get().mode;
        result.put("mode", mode);
        result.put("link", link);
        result.put("inboxEnabled", supportSettings.get().inboxEnabled);
        result.put("available", "internal".equals(mode) || ("external".equals(mode) && !link.isEmpty()));
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取投诉邮箱
     */
    @GetMapping("/complaint/email")
    public ResponseEntity<?> getComplaintEmail() {
        String email = systemConfigService.getConfigValue("complaint.email");
        
        Map<String, Object> result = new HashMap<>();
        result.put("email", email != null ? email : "");
        result.put("available", email != null && !email.isEmpty());
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取系统时区配置
     */
    @GetMapping("/share-templates")
    public ResponseEntity<?> getShareTemplates(@RequestParam(value = "locale", defaultValue = "en") String locale,
            @RequestParam(value = "details", defaultValue = "false") boolean details) {
        String value = systemConfigService.getConfigValue(SystemConfigService.SHARE_TEMPLATES_KEY);
        java.util.List<String> templates = SystemConfigService.shareTemplates(value, locale);
        if (!details) return ResponseEntity.ok(templates); // Preserve the legacy array response.
        Map<String, Object> result = new HashMap<>();
        result.put("templates", templates);
        result.put("focus", SystemConfigService.shareFocus(value));
        result.put("definitions", SystemConfigService.shareTemplateDefinitions(value, locale));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/system/timezone")
    public ResponseEntity<?> getSystemTimezone() {
        String timezone = systemConfigService.getConfigValue("system.timezone");
        
        Map<String, Object> result = new HashMap<>();
        result.put("timezone", timezone != null && !timezone.isEmpty() ? timezone : "Europe/London");
        return ResponseEntity.ok(result);
    }
}



