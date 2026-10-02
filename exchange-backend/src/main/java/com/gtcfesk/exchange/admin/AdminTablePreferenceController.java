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
        TablePreferenceValidation.table(table);
        com.gtcfesk.exchange.control.ControlIdentity identity=com.gtcfesk.exchange.control.ControlIdentity.current();
        if(com.gtcfesk.exchange.control.ControlIdentity.isAccess()){com.gtcfesk.exchange.tenant.TenantContext.require(identity.getTenantId());if(identity.getActorId()==null||identity.getActorId()<=0)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"总控身份无效");}
        boolean agent = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_AGENT"));
        return "tenant:"+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+":"+(com.gtcfesk.exchange.control.ControlIdentity.isAccess()?"control:":agent?"agent:":"admin:") + (com.gtcfesk.exchange.control.ControlIdentity.isAccess()?com.gtcfesk.exchange.control.ControlIdentity.actorId():auth.getName()) + ":" + table;
    }

    @GetMapping("/{table}")
    public JsonNode get(@PathVariable String table) throws java.io.IOException {
        Optional<AdminTablePreference> saved = repository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), key(table));
        if(!saved.isPresent()&&!com.gtcfesk.exchange.control.ControlIdentity.isAccess()){
            Authentication auth=SecurityContextHolder.getContext().getAuthentication();
            boolean agent=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_AGENT"));
            saved=repository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),(agent?"agent:":"admin:")+auth.getName()+":"+table);
        }
        return saved.isPresent() ? mapper.readTree(saved.get().getColumnsJson()) : mapper.createArrayNode();
    }

    @PutMapping("/{table}")
    public Map<String, Boolean> save(@PathVariable String table, @RequestBody JsonNode columns) {
        String id = key(table);
        TablePreferenceValidation.columns(columns);
        AdminTablePreference entity = new AdminTablePreference();
        entity.setId(id);
        entity.setColumnsJson(columns.toString());
        repository.save(entity);
        return Collections.singletonMap("success", true);
    }
}
