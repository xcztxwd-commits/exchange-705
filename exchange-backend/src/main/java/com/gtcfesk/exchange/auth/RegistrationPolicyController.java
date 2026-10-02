package com.gtcfesk.exchange.auth;

import com.gtcfesk.exchange.admin.SystemConfigService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exact GET /api/auth/register is public in SecurityConfig; no system-config values are returned. */
@RestController
@RequiredArgsConstructor
public class RegistrationPolicyController {
    private final SystemConfigService configs;
    @GetMapping("/api/auth/register")
    public ResponseEntity<Map<String, Object>> policy() {
        RegistrationFields fields = configs.registrationFields();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", fields.getPhone());
        body.put("annualIncome", fields.getAnnualIncome());
        body.put("currencies", RegistrationFields.currencies());
        body.put("maxAnnualIncome", RegistrationFields.MAX_INCOME);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
