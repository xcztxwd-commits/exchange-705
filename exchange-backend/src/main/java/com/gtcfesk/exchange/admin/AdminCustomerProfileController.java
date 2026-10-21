package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gtcfesk.exchange.config.AdminPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users/{userId}/profile")
@RequiredArgsConstructor
public class AdminCustomerProfileController {
    private final AdminCustomerProfileService profiles;

    @GetMapping
    @AdminPermission(menu = "users", action = "detail")
    public ResponseEntity<?> get(@PathVariable Long userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.get(userId));
    }

    @PostMapping
    @AdminPermission(menu = "users", action = "detail")
    public ResponseEntity<?> save(@PathVariable Long userId, @RequestBody JsonNode input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.save(userId, input));
    }
}
