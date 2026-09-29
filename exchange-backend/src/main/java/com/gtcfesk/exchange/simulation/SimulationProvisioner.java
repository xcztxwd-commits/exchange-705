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
    private volatile long catalogAt;
    public synchronized void catalog(String bearer) {
        if (!environment.enabled()) throw new IllegalStateException("Not a simulation process");
        if (System.currentTimeMillis() - catalogAt < 60000) return;
        Map<String,Object> snapshot = gateway.get("/catalog", bearer);
        new TransactionTemplate(transactions).execute(status -> {
            for (String table : Arrays.asList("trading_symbol", "option_duration", "loan_setting", "financial_product", "system_config")) {
                Object values = snapshot.get(table);
                if (!(values instanceof List)) throw new IllegalArgumentException("Incomplete simulation catalog");
                if (!"system_config".equals(table)) jdbc.update("UPDATE " + table + " SET " + ("trading_symbol".equals(table) ? "is_enabled" : "enabled") + "=FALSE");
                for (Map<String,Object> source : (List<Map<String,Object>>) values) {
                    Map<String,Object> row = new LinkedHashMap<>(); source.forEach((key,value) -> row.put(key.toLowerCase(Locale.ROOT), value));
                    if (row.isEmpty() || !row.containsKey("id") || row.keySet().stream().anyMatch(key -> !key.matches("[a-z][a-z0-9_]*")))
                        throw new IllegalArgumentException("Invalid catalog columns");
                    if ("system_config".equals(table)) {
                        String key = String.valueOf(row.get("config_key"));
                        List<Long> ids = jdbc.queryForList("SELECT id FROM system_config WHERE config_key=?", Long.class, key);
                        if (ids.isEmpty()) {
                            jdbc.update("INSERT INTO system_config(config_key,config_value,description,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", key, row.get("config_value"), row.get("description"));
                        } else {
                            jdbc.update("UPDATE system_config SET config_value=?,updated_at=CURRENT_TIMESTAMP WHERE config_key=?", row.get("config_value"), key);
                        }
                        continue;
                    }
                    // Update existing definitions without deleting orders or changing their saved pricing snapshots.
                    List<String> columns = new ArrayList<>(row.keySet()); columns.remove("id");
                    List<Object> args = new ArrayList<>(); for (String key : columns) args.add(row.get(key)); args.add(row.get("id"));
                    String assignments = String.join(",", columns.stream().map(key -> "`" + key + "`=?").toArray(String[]::new));
                    int updated = jdbc.update("UPDATE " + table + " SET " + assignments + " WHERE id=?", args.toArray());
                    if (updated == 0 && jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=?", Integer.class, row.get("id")) == 0) {
                        columns.add("id");
                        jdbc.update("INSERT INTO " + table + " (`" + String.join("`,`", columns) + "`) VALUES (" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")", args.toArray());
                    }
                }
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM deposit_setting", Integer.class) == 0) {
                jdbc.update("INSERT INTO deposit_setting(type,network,address,bank_name,bank_account,account_name,enabled,created_at,updated_at) VALUES ('digital','USDT-TRC20','SIMULATION-NOT-A-BLOCKCHAIN-ADDRESS',NULL,NULL,NULL,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                jdbc.update("INSERT INTO deposit_setting(type,network,address,bank_name,bank_account,account_name,enabled,created_at,updated_at) VALUES ('bank','USD',NULL,'SIMULATION — DO NOT SEND MONEY','SIMULATION-ONLY','Demo account',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            }
            return null;
        });
        catalogAt = System.currentTimeMillis(); quotes.requestSymbolRefresh();
    }
    public void user(Long userId) {
        if (!environment.enabled()) throw new IllegalStateException("Not a simulation process");
        new TransactionTemplate(transactions).execute(status -> {
            // MySQL's unique primary key serializes concurrent first-use across demo replicas.
            jdbc.update("INSERT IGNORE INTO user_account(id,row_version,email,password_hash,nickname,status,user_type,kyc_level,kyc_status,created_at,updated_at) VALUES (?,0,?,'SIMULATION-NO-LOCAL-LOGIN','Demo user','normal','normal',0,'NOT_VERIFIED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", userId, "demo-" + userId + "@example.invalid");
            jdbc.queryForObject("SELECT id FROM user_account WHERE id=? FOR UPDATE", Long.class, userId);
            int seeded = jdbc.queryForObject("SELECT COUNT(*) FROM simulation_seed WHERE user_id=?", Integer.class, userId);
            if (seeded == 0) {
                for (String coin : Arrays.asList("FUND", "CONTRACT", "OPTION")) {
                    jdbc.update("INSERT INTO asset_account(user_id,coin,available,frozen,row_version,created_at,updated_at) VALUES (?,?,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", userId, coin, new BigDecimal("100000"));
                }
                jdbc.update("INSERT INTO simulation_seed(user_id,amount_per_wallet,created_at) VALUES (?,100000,CURRENT_TIMESTAMP)", userId);
            }
            return null;
        });
    }
}
