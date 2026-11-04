package com.gtcfesk.exchange.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Server-to-server identity mirror; no credentials, KYC documents or wallet data cross this boundary. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 7) @RequiredArgsConstructor
public class SimulationProfileBoundary extends OncePerRequestFilter {
    static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "email", "nickname", "remark", "avatar_url", "country_code", "phone",
            "user_type", "parent_user_id", "invite_code", "my_invite_code", "status",
            "annual_income", "annual_income_currency", "created_at", "last_login_ip",
            "last_login_region", "last_login_at", "last_login_domain", "last_activity_at"));
    private final SimulationEnvironment environment;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    @Value("${simulation.inspection-key:}") private String key;

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/simulation/user-profiles".equals(request.getServletPath().isEmpty()
                ? request.getRequestURI() : request.getServletPath());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        String supplied = request.getHeader("X-Simulation-Inspection-Key");
        if (environment.enabled() || key.length() < 32 || supplied == null
                || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(403); return;
        }
        if (!"POST".equals(request.getMethod())) { response.setStatus(405); return; }
        Long tenant;
        List<Long> ids;
        try {
            tenant = Long.valueOf(request.getHeader("X-Simulation-Tenant-Id"));
            if (tenant <= 0) throw new IllegalArgumentException();
            List<?> requested = mapper.readValue(request.getInputStream(), List.class);
            if (requested.isEmpty() || requested.size() > 100) throw new IllegalArgumentException();
            ids = new ArrayList<>();
            for (Object id : requested) {
                long value = Long.parseLong(String.valueOf(id));
                if (value <= 0) throw new IllegalArgumentException();
                ids.add(value);
            }
        } catch (Exception invalid) { response.setStatus(400); return; }
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
            List<Object> args = new ArrayList<>(); args.add(tenant); args.addAll(ids);
            List<Map<String,Object>> rows = jdbc.queryForList("SELECT tenant_id,id," + String.join(",", COLUMNS)
                    + " FROM user_account WHERE tenant_id=? AND id IN ("
                    + String.join(",", Collections.nCopies(ids.size(), "?")) + ") ORDER BY id", args.toArray());
            List<Map<String,Object>> profiles = new ArrayList<>();
            for (Map<String,Object> row : rows) {
                Map<String,Object> profile = new LinkedHashMap<>();
                row.forEach((column, value) -> profile.put(column.toLowerCase(Locale.ROOT),
                        value instanceof java.sql.Timestamp ? value.toString() : value));
                profiles.add(profile);
            }
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("environment", "REAL"); result.put("tenantId", tenant); result.put("list", profiles);
            mapper.writeValue(response.getWriter(), result);
        }
    }
}
