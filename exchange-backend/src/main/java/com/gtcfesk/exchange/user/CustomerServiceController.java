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
    
    /**
     * 获取客服链接
     */
    @GetMapping("/customer-service/link")
    public ResponseEntity<?> getCustomerServiceLink() {
        String link = systemConfigService.getConfigValue("customer.service.link");
        
        Map<String, Object> result = new HashMap<>();
        result.put("link", link != null ? link : "");
        result.put("available", link != null && !link.isEmpty());
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
    @GetMapping("/system/timezone")
    public ResponseEntity<?> getSystemTimezone() {
        String timezone = systemConfigService.getConfigValue("system.timezone");
        
        Map<String, Object> result = new HashMap<>();
        result.put("timezone", timezone != null && !timezone.isEmpty() ? timezone : "Europe/London");
        return ResponseEntity.ok(result);
    }
}



