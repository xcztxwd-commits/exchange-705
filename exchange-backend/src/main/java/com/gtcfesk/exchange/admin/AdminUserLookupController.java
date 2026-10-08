package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.simulation.AdminReadRoutes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController @RequiredArgsConstructor @RequestMapping("/api/admin/user-lookup")
public class AdminUserLookupController {
    private final UserAccountRepository users;
    private final AdminPermissionService permissions;

    @GetMapping("/{scope}")
    public List<Map<String,Object>> search(@PathVariable String scope, @RequestParam String query,
                                           @RequestParam(required = false) Long filterAgentId) {
        String menu = AdminReadRoutes.permission("GET", "/api/admin/user-lookup/" + scope);
        permissions.require(menu, "view");
        String value = query.trim();
        if (value.isEmpty()) return Collections.emptyList();
        if (value.length() > 254) throw new IllegalArgumentException("搜索内容不能超过 254 字符");
        Long callerAgent = BackendAccess.agentId();
        String pattern = AdminUserIdentity.emailPattern(value);
        return users.findLookupUsers(callerAgent == null ? filterAgentId : callerAgent,
                "agents".equals(scope) ? "agent" : null, pattern, pattern, PageRequest.of(0, 20))
                .stream().map(user -> {
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("userId", user.getId());
                    row.put("email", user.getEmail());
                    return row;
                }).collect(Collectors.toList());
    }
}
