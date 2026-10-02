package com.gtcfesk.exchange.simulation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import java.util.*;
import java.math.BigDecimal;

@Service @RequiredArgsConstructor
public class SimulationProvisioner {
    private final SimulationEnvironment environment;
    private final SimulationGateway gateway;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final ForexQuoteMarketService quotes;
    private final java.util.Map<Long,Long> catalogAt = new java.util.HashMap<>();
    public synchronized void catalog(String bearer) {
        if (!environment.enabled()) throw new IllegalStateException("Not a simulation process");
        Long tenant = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        if (System.currentTimeMillis() - catalogAt.getOrDefault(tenant, 0L) < 60000) return;
        Map<String,Object> snapshot = gateway.get("/catalog", bearer);
        new TransactionTemplate(transactions).execute(status -> {
            for (String table : Arrays.asList("trading_symbol", "option_duration", "loan_setting", "financial_product", "system_config")) {
                Object values = snapshot.get(table);
                if (!(values instanceof List)) throw new IllegalArgumentException("Incomplete simulation catalog");
                if (!"system_config".equals(table)) jdbc.update("UPDATE " + table + " SET " + ("trading_symbol".equals(table) ? "is_enabled" : "enabled") + "=FALSE WHERE tenant_id=?", tenant);
                for (Map<String,Object> source : (List<Map<String,Object>>) values) {
                    Map<String,Object> row = new LinkedHashMap<>(); source.forEach((key,value) -> row.put(key.toLowerCase(Locale.ROOT), value));
                    if (row.isEmpty() || !row.containsKey("id") || row.keySet().stream().anyMatch(key -> !key.matches("[a-z][a-z0-9_]*")))
                        throw new IllegalArgumentException("Invalid catalog columns");
                    if (!(row.get("tenant_id") instanceof Number) || ((Number)row.get("tenant_id")).longValue() != tenant) throw new IllegalArgumentException("Catalog tenant mismatch");
                    if ("system_config".equals(table)) {
                        String key = String.valueOf(row.get("config_key"));
                        List<Long> ids = jdbc.queryForList("SELECT id FROM system_config WHERE tenant_id=? AND config_key=?", Long.class, tenant, key);
                        if (ids.isEmpty()) {
                            jdbc.update("INSERT INTO system_config(tenant_id,config_key,config_value,description,created_at,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", tenant, key, row.get("config_value"), row.get("description"));
                        } else {
                            jdbc.update("UPDATE system_config SET config_value=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND config_key=?", row.get("config_value"), tenant, key);
                        }
                        continue;
                    }
                    // Update existing definitions without deleting orders or changing their saved pricing snapshots.
                    List<String> columns = new ArrayList<>(row.keySet()); columns.remove("id"); columns.remove("tenant_id");
                    List<Object> args = new ArrayList<>(); for (String key : columns) args.add(row.get(key)); args.add(row.get("id")); args.add(tenant);
                    String assignments = String.join(",", columns.stream().map(key -> "`" + key + "`=?").toArray(String[]::new));
                    int updated = jdbc.update("UPDATE " + table + " SET " + assignments + " WHERE id=? AND tenant_id=?", args.toArray());
                    if (updated == 0 && jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=? AND tenant_id=?", Integer.class, row.get("id"), tenant) == 0) {
                        columns.add("id"); columns.add("tenant_id");
                        jdbc.update("INSERT INTO " + table + " (`" + String.join("`,`", columns) + "`) VALUES (" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")", args.toArray());
                    }
                }
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM deposit_setting WHERE tenant_id=?", Integer.class, tenant) == 0) {
                jdbc.update("INSERT INTO deposit_setting(tenant_id,type,network,address,bank_name,bank_account,account_name,enabled,created_at,updated_at) VALUES (?,'digital','USDT-TRC20','SIMULATION-NOT-A-BLOCKCHAIN-ADDRESS',NULL,NULL,NULL,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", tenant);
                jdbc.update("INSERT INTO deposit_setting(tenant_id,type,network,address,bank_name,bank_account,account_name,enabled,created_at,updated_at) VALUES (?,'bank','USD',NULL,'SIMULATION — DO NOT SEND MONEY','SIMULATION-ONLY','Demo account',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", tenant);
            }
            return null;
        });
        catalogAt.put(tenant, System.currentTimeMillis()); quotes.requestSymbolRefresh();
    }
    public void user(Long userId) {
        Long tenant = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        if (!environment.enabled()) throw new IllegalStateException("Not a simulation process");
        new TransactionTemplate(transactions).execute(status -> {
            // A duplicate-key UPDATE acquires an exclusive lock directly; INSERT IGNORE would
            // leave shared locks that deadlock when concurrent requests upgrade to FOR UPDATE.
            jdbc.update("INSERT INTO user_account(tenant_id,id,row_version,email,password_hash,nickname,status,user_type,kyc_level,kyc_status,created_at,updated_at) VALUES (?,?,0,?,'SIMULATION-NO-LOCAL-LOGIN','Demo user','normal','normal',0,'NOT_VERIFIED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE id=id", tenant, userId, "demo-" + userId + "@example.invalid");
            jdbc.queryForObject("SELECT id FROM user_account WHERE tenant_id=? AND id=? FOR UPDATE", Long.class, tenant, userId);
            int seeded = jdbc.queryForObject("SELECT COUNT(*) FROM simulation_seed WHERE tenant_id=? AND user_id=?", Integer.class, tenant, userId);
            if (seeded == 0) {
                for (String coin : Arrays.asList("FUND", "CONTRACT", "OPTION")) {
                    jdbc.update("INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version,created_at,updated_at) VALUES (?,?,?,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", tenant, userId, coin, new BigDecimal("100000"));
                }
                jdbc.update("INSERT INTO simulation_seed(tenant_id,user_id,amount_per_wallet,created_at) VALUES (?,?,100000,CURRENT_TIMESTAMP)", tenant, userId);
            }
            return null;
        });
    }
}
