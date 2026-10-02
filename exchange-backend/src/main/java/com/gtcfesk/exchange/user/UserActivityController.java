package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.config.BackendAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;

@RestController @RequiredArgsConstructor
public class UserActivityController {
    private final UserActivityService activity;
    private final BackendAccess access;
    @PostMapping("/api/auth/activity")
    public Map<String,Object> report(@RequestBody Map<String,Object> body, Authentication auth) {
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) throw new AccessDeniedException("无权访问");
        if (!body.keySet().equals(new HashSet<>(Arrays.asList("pageCode", "deviceType", "sequence"))) || !(body.get("sequence") instanceof Number)) throw new IllegalArgumentException("页面上报参数无效");
        activity.report(Long.valueOf(auth.getName()), (String) body.get("pageCode"), (String) body.get("deviceType"), ((Number) body.get("sequence")).longValue());
        return Collections.singletonMap("success", true);
    }
    @GetMapping("/api/admin/users/online")
    public Map<String,Object> online(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size, @RequestParam(required=false) String userEmail) {
        if (!access.canReadMenu("users")) throw new AccessDeniedException("无权访问");
        return activity.list(BackendAccess.agentId(), page, size, userEmail);
    }
}
