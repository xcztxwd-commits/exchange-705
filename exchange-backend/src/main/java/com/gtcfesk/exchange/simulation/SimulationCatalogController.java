package com.gtcfesk.exchange.simulation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;

/** Authenticated, public-product-only snapshot; never includes users, keys or payout destinations. */
@RestController @RequestMapping("/api/simulation") @RequiredArgsConstructor
public class SimulationCatalogController {
    private final JdbcTemplate jdbc;
    private final SimulationEnvironment environment;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService policy;
    public static final List<String> TABLES = Arrays.asList("trading_symbol", "option_duration", "loan_setting", "financial_product");
    @GetMapping("/session") public Object session(Authentication auth) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("tenantId", com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()); result.put("userId", Long.valueOf(auth.getName())); result.put("environment", environment.enabled() ? "DEMO" : "REAL");
        com.gtcfesk.exchange.control.Tenant tenant=policy.current();result.put("acceptNewBusiness","ACTIVE".equals(tenant.getStatus())&&tenant.isConfigReady()&&tenant.isDomainVerified());
        Map<String,Boolean> features=new LinkedHashMap<>();for(String feature:com.gtcfesk.exchange.control.TenantPolicyService.FEATURES)features.put(feature,policy.featureEnabled(feature));result.put("features",features);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
    }
    @GetMapping("/catalog") public Object catalog() {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String table : TABLES) {
            List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id=?", com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
            for (Map<String,Object> row : rows) for (String column : new ArrayList<>(row.keySet())) {
                String key = column.toLowerCase(Locale.ROOT);
                Object value = row.get(column);
                // Control plans are not public catalog data and must not cross environments.
                if (key.startsWith("control_") || key.startsWith("random_market_")) {
                    row.put(column, key.endsWith("enabled") ? false : key.equals("control_price_offset") ? 0 : null);
                } else if (value instanceof java.sql.Timestamp) row.put(column, value.toString());
            }
            result.put(table, rows);
        }
        result.put("system_config", jdbc.queryForList("SELECT * FROM system_config WHERE config_key IN ('home.categories','system.timezone','market.conversion.currencies') AND tenant_id=?", com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()));
        // Normalize SQL timestamps consistently before JSON transport.
        for (Object rows : result.values()) for (Map<String,Object> row : (List<Map<String,Object>>) rows)
            row.replaceAll((key,value) -> value instanceof java.sql.Timestamp ? value.toString() : value);
        result.put("tenantId", com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
    }
}
