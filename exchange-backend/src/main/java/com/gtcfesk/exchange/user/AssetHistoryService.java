package com.gtcfesk.exchange.user;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.sql.Timestamp;
import java.util.*;

@Service @RequiredArgsConstructor
public class AssetHistoryService {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AssetEquityHistoryService equity;
    @org.springframework.beans.factory.annotation.Value("${asset.history.equity.read-enabled:false}")
    private boolean equityRead;
    @org.springframework.beans.factory.annotation.Value("${asset.history.equity.collect-enabled:false}")
    private boolean equityCollect;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.tenant.TenantJobRunner tenantJobs;
    // Same accounting boundary for the headline and every historical sample.
    // Frozen money remains owned; internal transfers must not appear as gains/losses.
    private static final String TOTAL = "coalesce(sum(coalesce(available,0)+coalesce(frozen,0)),0)";
    private static final String ACCOUNTS = "upper(coin) in ('FUND','CONTRACT','OPTION')";

    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void capture() {
        if (equityCollect) return;
        tenantJobs.each("asset-history",tenant -> captureTenant());
    }
    private void captureTenant() {
        jdbc.update("insert into asset_snapshot (tenant_id,user_id,captured_at,total) select tenant_id,user_id,?," + TOTAL
                + " from asset_account where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and " + ACCOUNTS + " group by tenant_id,user_id", System.currentTimeMillis());
    }

    /** Retained compatibility entry point; raw history is never automatically deleted. */
    public void prune() { }

    public static long rangeMillis(String range) {
        switch (range) {
            case "1D": return 86400000L;
            case "1W": return 7L * 86400000;
            case "1M": return 30L * 86400000;
            case "1Y": return 365L * 86400000;
            default: throw new IllegalArgumentException("Unsupported asset history range");
        }
    }

    public Map<String, Object> history(Long userId, String range) {
        if (equityRead) return equity.history(userId, range);
        long duration = rangeMillis(range); // Validate before database access.
        List<String> zones = jdbc.query("select config_value from system_config where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and config_key='system.timezone'", (rs, row) -> rs.getString(1));
        ZoneId zone;
        try { zone = ZoneId.of(zones.isEmpty() ? "Europe/London" : zones.get(0)); }
        catch (DateTimeException | NullPointerException e) { zone = ZoneId.of("Europe/London"); }
        long now = System.currentTimeMillis(), from = now - duration;
        long bucket = bucketMillis(range);
        List<Map<String, Object>> points = jdbc.query(
                "select captured_at,total from asset_snapshot where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and id in (select max(id) from asset_snapshot "
                + "where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and captured_at>=? and captured_at<=? group by floor((captured_at-?) / ?)) "
                + "or id=(select min(id) from asset_snapshot where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and captured_at>=? and captured_at<=?) "
                + "or id=(select id from asset_snapshot where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and captured_at>=? and captured_at<=? order by total asc,captured_at asc limit 1) "
                + "or id=(select id from asset_snapshot where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and captured_at>=? and captured_at<=? order by total desc,captured_at asc limit 1) order by captured_at",
                (rs, row) -> point(rs.getLong(1), rs.getBigDecimal(2)), userId, from, now, from, bucket, userId, from, now, userId, from, now, userId, from, now);
        BigDecimal current = jdbc.queryForObject("select " + TOTAL + " from asset_account where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and " + ACCOUNTS,
                BigDecimal.class, userId);
        List<Map<String, Object>> opening = jdbc.query("select captured_at,total from asset_snapshot where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and captured_at<=? and captured_at>=? order by captured_at desc limit 1",
                (rs, row) -> { Map<String,Object> p = point(from, rs.getBigDecimal(2)); p.put("observedAt", rs.getLong(1)); return p; }, userId, from, from - 120000);
        // Carry only a recent known opening balance, never assume missing history was zero.
        if (!opening.isEmpty() && (points.isEmpty() || (Long) points.get(0).get("time") > from)) points.add(0, opening.get(0));
        boolean hasHistory = !points.isEmpty();
        points.add(point(now, current));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("points", points);
        result.put("total", current.toPlainString());
        result.put("asOf", now);
        result.put("from", from);
        result.put("intervalMs", bucket);
        result.put("hasHistory", hasHistory);
        result.put("currency", "USD");
        result.put("basis", "available_plus_frozen");
        result.put("schemaVersion", 1);
        result.put("basisVersion", "wallet_balance_v1");
        long incomeFrom = periodStart(range, Instant.ofEpochMilli(now), zone);
        BigDecimal earned = income(userId, incomeFrom, now);
        BigDecimal principal = incomeOpening(userId, incomeFrom, now);
        result.put("income", earned.toPlainString());
        result.put("incomeOpening", principal.toPlainString());
        result.put("incomePercent", incomePercent(earned, principal));
        result.put("incomeFrom", incomeFrom);
        result.put("incomeBasis", "settled_net_profit_and_paid_yield");
        result.put("timezone", zone.getId());
        return result;
    }

    BigDecimal incomeOpening(Long userId, long start, long now) {
        List<BigDecimal> values = jdbc.query("select total from asset_snapshot where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and captured_at<=? and captured_at>=? order by captured_at desc,id desc limit 1",
                (rs, row) -> rs.getBigDecimal(1), userId, start, start - 120000);
        // Product fallback: earliest positive observed assets, not reconstructed deposits.
        if (values.isEmpty() || values.get(0).signum() == 0) {
            values = jdbc.query("select total from asset_snapshot where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and captured_at<=? and total>0 order by captured_at asc,id asc limit 1",
                    (rs, row) -> rs.getBigDecimal(1), userId, now);
        }
        return values.isEmpty() ? BigDecimal.ZERO : values.get(0);
    }

    static String incomePercent(BigDecimal earned, BigDecimal principal) {
        if (principal.signum() == 0) return earned.signum() == 0 ? "0.00" : null;
        return earned.multiply(new BigDecimal("100")).divide(principal, 2, RoundingMode.HALF_UP).toPlainString();
    }

    public static long bucketMillis(String range) {
        switch (range) {
            case "1D": return 60000L;
            case "1W": return 3600000L;
            case "1M": return 4L * 3600000;
            case "1Y": return 86400000L;
            default: throw new IllegalArgumentException("Unsupported asset history range");
        }
    }

    public static long periodStart(String range, Instant now, ZoneId zone) {
        LocalDate day = now.atZone(zone).toLocalDate();
        switch (range) {
            case "1D": break;
            case "1W": day = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); break;
            case "1M": day = day.withDayOfMonth(1); break;
            case "1Y": day = day.withDayOfYear(1); break;
            default: throw new IllegalArgumentException("Unsupported asset history range");
        }
        return day.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    BigDecimal income(Long userId, long from, long to) {
        // Orders use LocalDateTime.now(): translate the calendar boundaries to the
        // server's storage timezone before querying the timestamp-without-zone columns.
        Timestamp start = Timestamp.valueOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(from), ZoneId.systemDefault()));
        Timestamp end = Timestamp.valueOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(to), ZoneId.systemDefault()));
        BigDecimal options = jdbc.queryForObject("select coalesce(sum(profit),0) from option_order where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and status='CLOSED' and close_time>=? and close_time<=?", BigDecimal.class, userId, start, end);
        BigDecimal contracts = jdbc.queryForObject("select coalesce(sum(coalesce(profit,0)-case when lot_size is not null then coalesce(fee,0) else 0 end),0) from contract_order where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and status='CLOSED' and close_time>=? and close_time<=?", BigDecimal.class, userId, start, end);
        BigDecimal yield = jdbc.queryForObject("select coalesce(sum(daily_yield),0) from financial_yield_record where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and status='PAID' and paid_at>=? and paid_at<=?", BigDecimal.class, userId, start, end);
        return options.add(contracts).add(yield);
    }

    private static Map<String, Object> point(long time, BigDecimal total) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("time", time); point.put("value", total.toPlainString());
        return point;
    }
}
