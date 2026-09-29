package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.AdminTablePreference;
import com.gtcfesk.exchange.repository.AdminTablePreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Presentation preferences only; never grants access to columns or business data. */
@RestController
@RequestMapping("/api/admin/table-preferences")
@RequiredArgsConstructor
public class AdminTablePreferenceController {
    private final AdminTablePreferenceRepository repository;
    private final ObjectMapper mapper;

    private String key(String table) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a ->
                Arrays.asList("ROLE_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_AGENT").contains(a.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请使用后台账号登录");
        if (!table.matches("[A-Za-z0-9_.-]{1,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "表格标识无效");
        boolean agent = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_AGENT"));
        return (agent ? "agent:" : "admin:") + auth.getName() + ":" + table;
    }

    @GetMapping("/{table}")
    public JsonNode get(@PathVariable String table) throws java.io.IOException {
        Optional<AdminTablePreference> saved = repository.findById(key(table));
        return saved.isPresent() ? mapper.readTree(saved.get().getColumnsJson()) : mapper.createArrayNode();
    }

    @PutMapping("/{table}")
    public Map<String, Boolean> save(@PathVariable String table, @RequestBody JsonNode columns) {
        String id = key(table);
        if (!columns.isArray() || columns.size() > 150 || columns.toString().length() > 60000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "列配置无效");
        Set<String> ids = new HashSet<>();
        boolean visible = columns.size() == 0;
        for (JsonNode column : columns) {
            if (!column.isObject() || column.size() != 3 || !column.path("id").isTextual() ||
                    column.path("id").asText().isEmpty() || column.path("id").asText().length() > 200 ||
                    !ids.add(column.path("id").asText()) || !column.path("visible").isBoolean() ||
                    !column.path("fixed").isTextual() || !Arrays.asList("", "left", "right").contains(column.path("fixed").asText()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "列配置无效");
            visible |= column.path("visible").asBoolean();
        }
        if (!visible) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少保留一列");
        AdminTablePreference entity = new AdminTablePreference();
        entity.setId(id);
        entity.setColumnsJson(columns.toString());
        repository.save(entity);
        return Collections.singletonMap("success", true);
    }
}
