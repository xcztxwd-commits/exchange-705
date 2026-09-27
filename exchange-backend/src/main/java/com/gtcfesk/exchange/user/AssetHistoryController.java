package com.gtcfesk.exchange.user;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Collections;

@RestController @RequestMapping("/api/user/asset-history") @RequiredArgsConstructor
public class AssetHistoryController {
    private final AssetHistoryService history;

    @GetMapping
    public ResponseEntity<?> get(@RequestParam(defaultValue = "1M") String range, Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName()))
            return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(history.history(Long.valueOf(auth.getName()), range));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("message", "Invalid asset history request"));
        }
    }
}
