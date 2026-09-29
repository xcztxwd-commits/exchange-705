package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.security.WebsiteSecuritySettings;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/website-security")
@RequiredArgsConstructor
public class WebsiteSecurityController {
    private final WebsiteSecuritySettings settings;
    @GetMapping
    @com.gtcfesk.exchange.config.AdminPermission(menu = "website_security", action = "")
    public WebsiteSecuritySettings.Policy read() { return settings.read(); }
    @PutMapping
    @com.gtcfesk.exchange.config.AdminPermission(menu = "website_security", action = "save")
    public WebsiteSecuritySettings.Policy save(@RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        settings.save(WebsiteSecuritySettings.parse(body.toString())); return settings.read();
    }
}
