package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Bounded, insert-only historical source repair. No quotes, controls, Redis or projection publication. */
@Service
public class SourceHistoryGapRepair {
    private final ControlHistoryStore store;
    public SourceHistoryGapRepair(ControlHistoryStore store) { this.store = store; }
    static final class Window {
        final long tenant, symbol, version, from, to, cursor;
        final int limit;
        final String code, category, configuredSource, provider, period, identity, key;
        final boolean continuous, projection;
        final List<Map<String,Object>> gaps = new ArrayList<>();
        boolean discovery;
        Window(TradingSymbol config, String period, int limit, long cursor, String provider, long from, long to, boolean projection) {
            tenant = TenantContext.requireTenantId(); symbol = config.getId(); version = config.getRowVersion();
            code = ForexQuoteMarketService.marketCode(config); category = config.getSourceCategory();
            configuredSource = config.getMarketSource(); this.provider = provider; this.period = period;
            this.limit = limit; this.cursor = cursor; this.from = from; this.to = to; this.projection = projection;
            continuous = continuous(category);
            identity = provider + ":" + category + ":" + code;
            key = "history-repair:" + tenant + ":" + symbol + ":" + identity + ":" + version + ":" + period + ":" + from + ":" + to;
        }
        boolean needsSource() { return discovery || gaps.stream().anyMatch(gap -> Boolean.TRUE.equals(gap.get("recoverable"))); }
    }
    static final class Receipt {
        final long at = System.currentTimeMillis();
        final List<Map<String,Object>> gaps;
        final int inserted;
        Receipt(List<Map<String,Object>> gaps, int inserted) { this.gaps = gaps; this.inserted = inserted; }
    }
    static boolean continuous(String category) { return "Crypto".equalsIgnoreCase(category) || "CryptoPerpetual".equalsIgnoreCase(category); }
    static long start(String period, long at) {
        if ("1M".equals(period)) return RandomMarketPath.monthStart(at);
        long width = RandomMarketPath.duration(period), offset = "1w".equals(period) ? 4 * 86400000L : 0;
        return Math.floorDiv(at - offset, width) * width + offset;
    }
    static long previous(String period, long at) {
        return "1M".equals(period) ? Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).minusMonths(1).toInstant().toEpochMilli()
                : at - RandomMarketPath.duration(period);
    }
    static long end(String period, long at) { return RandomMarketPath.periodEnd(period, at); }
    static Map<String,Object> gap(long from, long to, String reason, boolean recoverable) {
        Map<String,Object> row = new LinkedHashMap<>(); row.put("from", from); row.put("to", to);
        row.put("reason", reason); row.put("recoverable", recoverable); return row;
    }
    /** Count is not coverage. Continuous sources also check internal slots and the cursor-adjacent tail. */
    Window inspect(TradingSymbol config, String period, int limit, long cursor, String provider,
            List<Map<String,Object>> cached, boolean projection) {
        TenantContext.require(config.getTenantId());
        long now = System.currentTimeMillis(), to = Math.min(cursor, now - 1), from;
        if (continuous(config.getSourceCategory())) {
            to = start(period, to);
            if (end(period, to) > now) to = previous(period, to);
            from = to; for (int i = 1; i < limit; i++) from = previous(period, from);
        } else {
            // A session market has no inferred UTC slot calendar. Only actual upstream bars prove existence.
            from = Math.max(946684800000L, to - Math.max(7 * 86400000L, limit * RandomMarketPath.duration(period) * 3));
        }
        Window window = new Window(config, period, limit, cursor, provider, Math.max(946684800000L, from), to, projection);
        Protection protection = protection(window);
        String configured = MarketInstrumentCatalog.inferredSource(config.getSourceCategory());
        if (configured == null || !configured.equalsIgnoreCase(config.getMarketSource()))
            protection.add(window.from, Long.MAX_VALUE, "source_route_conflict");
        Map<Long,Map<String,Object>> stored = existing(window);
        if (window.continuous) {
            for (long at = window.from; at <= window.to; at = end(period, at)) {
                Map<String,Object> old = stored.get(at);
                String reason = old == null ? protection.reason(at, end(period, at)) : existingProblem(window, old);
                if (reason == null) reason = protection.report(at, end(period, at));
                if (old == null || reason != null) window.gaps.add(gap(at, end(period, at) - 1,
                        reason == null ? "missing_source" : reason, old == null && reason == null));
            }
        } else {
            Set<Long> observed = new TreeSet<>(stored.keySet());
            for (Map<String,Object> row : cached) observed.add(ControlHistoryStore.time(row));
            for (Long at : observed) {
                if (at < window.from || at > window.to || end(period, at) > now) continue;
                Map<String,Object> old = stored.get(at);
                String reason = old == null ? protection.reason(at, end(period, at)) : existingProblem(window, old);
                if (reason == null) reason = protection.report(at, end(period, at));
                if (old == null || reason != null) window.gaps.add(gap(at, end(period, at) - 1,
                        reason == null ? "missing_source" : reason, old == null && reason == null));
            }
            String refusal = protection.reason(window.from, window.to + 1);
            window.discovery = refusal == null;
            if (refusal != null && window.gaps.isEmpty()) window.gaps.add(gap(window.from, window.to, refusal, false));
        }
        return window;
    }
    private String currentRead() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? " FOR UPDATE" : "";
    }
    private Map<Long,Map<String,Object>> existing(Window window) {
        Map<Long,Map<String,Object>> result = new TreeMap<>();
        for (Map<String,Object> row : store.db.queryForList("SELECT candle_at,body,received_at FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period=? AND candle_at>=? AND candle_at<=? ORDER BY candle_at DESC LIMIT 200" + currentRead(),
                window.tenant, window.symbol, window.period, window.from, window.to)) result.put(((Number)row.get("candle_at")).longValue(), row);
        return result;
    }
    private String existingProblem(Window window, Map<String,Object> saved) {
        try {
            Map<String,Object> body = store.decode((String)saved.get("body"));
            long at = ((Number)saved.get("candle_at")).longValue();
            if (body.containsKey("historySource") && !window.identity.equals(body.get("historySource"))) return "source_conflict";
            if (body.containsKey("source") && !Arrays.asList(window.provider, window.configuredSource, "External").contains(body.get("source"))) return "source_conflict";
            if (ControlHistoryStore.time(body) != at || !valid(body) || Boolean.TRUE.equals(body.get("partial"))
                    || ((Number)saved.get("received_at")).longValue() < end(window.period, at) - 1) return "existing_partial_or_invalid";
            return null;
        } catch (RuntimeException invalid) { return "existing_partial_or_invalid"; }
    }
    static boolean valid(Map<String,Object> row) {
        try {
            BigDecimal open = finite(row.get("open_price")), close = finite(row.get("close_price"));
            BigDecimal high = finite(row.get("high_price")), low = finite(row.get("low_price")), volume = finite(row.get("volume"));
            return open.signum() > 0 && close.signum() > 0 && low.signum() > 0 && volume.signum() >= 0
                    && high.compareTo(open.max(close)) >= 0 && low.compareTo(open.min(close)) <= 0 && high.compareTo(low) >= 0;
        } catch (RuntimeException invalid) { return false; }
    }
    private static BigDecimal finite(Object value) {
        if (!(value instanceof Number) || !Double.isFinite(((Number)value).doubleValue())) throw new IllegalArgumentException("Invalid OHLCV");
        return ControlHistoryStore.number(value);
    }
    private static final class ProtectedRange {
        final long from, to; final String reason;
        ProtectedRange(long from, long to, String reason) { this.from = from; this.to = to; this.reason = reason; }
    }
    private static final class Protection {
        final List<ProtectedRange> ranges = new ArrayList<>();
        String reason(long from, long exclusiveEnd) {
            for (ProtectedRange range : ranges) if (from <= range.to && exclusiveEnd > range.from) return range.reason;
            return null;
        }
        String report(long from, long exclusiveEnd) {
            // A present source bar cannot recover a missing control sample or widen SOURCE publication eligibility.
            for (ProtectedRange range : ranges) if (from <= range.to && exclusiveEnd > range.from
                    && Arrays.asList("missing_control_samples", "control_samples_unverified", "projection_before_initial").contains(range.reason)) return range.reason;
            return null;
        }
        void add(long from, long to, String reason) { ranges.add(new ProtectedRange(from, to, reason)); }
    }
    private void protect(Protection result, Window window, long from, long to, String reason) {
        if ("1m".equals(window.period)) {
            if (!window.continuous) { result.add(window.from, Long.MAX_VALUE, reason); return; }
            // Source minutes feed all coarse controlled aggregates. Protect their entire enclosing buckets,
            // including Monday weeks crossing a month, not just the second occupied by a control sample.
            for (String period : Arrays.asList("1w", "1M")) result.add(start(period, from), end(period, start(period, to)) - 1, reason);
        } else result.add(from, to, reason);
    }
    private Protection protection(Window window) {
        Protection result = new Protection();
        boolean minutes = "1m".equals(window.period);
        long from = minutes ? Math.min(start("1M", window.from), start("1w", window.from)) : window.from;
        long to = minutes ? Math.max(end("1M", start("1M", window.to)), end("1w", start("1w", window.to))) - 1 : end(window.period, window.to) - 1;
        List<Long> cutovers = store.db.queryForList("SELECT from_minute FROM market_history_ordering WHERE tenant_id=? AND symbol_id=?" + currentRead(), Long.class, window.tenant, window.symbol);
        for (Long cutover : cutovers) result.add(0, cutover - 1, "sealed_history");
        List<Map<String,Object>> tasks = store.db.queryForList("SELECT t.id,t.started_at,t.planned_end,t.ended_at,t.sampled_until,COALESCE(f.finished_at,CASE WHEN f.task_id IS NOT NULL THEN 9223372036854775807 ELSE COALESCE(t.ended_at,9223372036854775807) END) AS protected_to FROM market_control_task t LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id WHERE t.tenant_id=? AND t.symbol_id=? AND t.started_at<=? AND COALESCE(f.finished_at,CASE WHEN f.task_id IS NOT NULL THEN 9223372036854775807 ELSE COALESCE(t.ended_at,9223372036854775807) END)>=? ORDER BY t.started_at LIMIT 201" + currentRead(), window.tenant, window.symbol, to, from);
        if (tasks.size() > 200) result.add(window.from, Long.MAX_VALUE, "protection_window_limit");
        for (Map<String,Object> task : tasks) {
            long first = ((Number)task.get("started_at")).longValue(), last = ((Number)task.get("protected_to")).longValue();
            long sampleFrom = first + Math.max(0, (window.from - first + 999) / 1000) * 1000;
            long observedUntil = task.get("ended_at") == null ? ((Number)task.get("sampled_until")).longValue()
                    : ((Number)task.get("ended_at")).longValue();
            long sampleTo = Math.min(end(window.period, window.to) - 1, Math.min(last,
                Math.min(((Number)task.get("planned_end")).longValue(), observedUntil)));
            String reason = "protected_control_history";
            if (sampleFrom <= sampleTo) {
                long expected = (sampleTo - sampleFrom) / 1000 + 1;
                List<Long> samples = store.db.queryForList("SELECT generated_at FROM market_control_sample WHERE tenant_id=? AND task_id=? AND generated_at>=? AND generated_at<=? ORDER BY generated_at LIMIT 513" + currentRead(),
                    Long.class, window.tenant, task.get("id"), sampleFrom, sampleTo);
                boolean missing = samples.size() < Math.min(expected, 513);
                for (int i = 0; !missing && i < samples.size(); i++) missing = samples.get(i) != sampleFrom + i * 1000L;
                reason = missing ? "missing_control_samples" : expected > 512 ? "control_samples_unverified" : reason;
            }
            // Open-ended flows never overflow calendar arithmetic. Sample loss is reported, never inferred from OHLC.
            protect(result, window, first, Math.min(last, to), reason);
        }
        List<Map<String,Object>> holds = store.db.queryForList("SELECT h.activated_at,h.released_at FROM market_control_hold h JOIN market_control_task t ON t.tenant_id=h.tenant_id AND t.id=h.task_id WHERE t.tenant_id=? AND t.symbol_id=? AND h.activated_at<=? AND COALESCE(h.released_at,9223372036854775807)>=? LIMIT 201" + currentRead(), window.tenant, window.symbol, to, from);
        if (holds.size() > 200) result.add(window.from, Long.MAX_VALUE, "protection_window_limit");
        for (Map<String,Object> hold : holds) protect(result, window, ((Number)hold.get("activated_at")).longValue(),
                hold.get("released_at") == null ? to : Math.min(to, ((Number)hold.get("released_at")).longValue()), "protected_control_hold");
        List<Map<String,Object>> publications = store.db.queryForList("SELECT p.from_at,p.to_at FROM market_control_publication p JOIN market_control_task t ON t.tenant_id=p.tenant_id AND t.id=p.task_id WHERE t.tenant_id=? AND t.symbol_id=? AND p.from_at<=? AND p.to_at>=? LIMIT 201" + currentRead(), window.tenant, window.symbol, to, from);
        if (publications.size() > 200) result.add(window.from, Long.MAX_VALUE, "protection_window_limit");
        for (Map<String,Object> publication : publications)
            protect(result, window, ((Number)publication.get("from_at")).longValue(), ((Number)publication.get("to_at")).longValue(), "protected_publication");
        for (String table : Arrays.asList("market_mixed_minute", "market_legacy_minute_snapshot")) {
            List<Long> keys = store.db.queryForList("SELECT minute_at FROM " + table + " WHERE tenant_id=? AND symbol_id=? AND minute_at>=? AND minute_at<=? ORDER BY minute_at LIMIT 501" + currentRead(), Long.class, window.tenant, window.symbol, from, to);
            if (keys.size() > 500) result.add(window.from, Long.MAX_VALUE, "protection_window_limit");
            for (Long at : keys) protect(result, window, at, at + 59999, "market_mixed_minute".equals(table) ? "protected_mixed_minute" : "frozen_snapshot");
        }
        List<Long> simulations = store.db.queryForList("SELECT candle_at FROM market_simulation_source_candle WHERE tenant_id=? AND symbol_id=? AND candle_at>=? AND candle_at<=? ORDER BY candle_at LIMIT 501" + currentRead(), Long.class, window.tenant, window.symbol, from, to);
        if (simulations.size() > 500) result.add(window.from, Long.MAX_VALUE, "protection_window_limit");
        for (Long at : simulations) protect(result, window, at, at + 59999, "simulation_history");
        if (window.projection && minutes) for (Long initial : store.db.queryForList("SELECT initial_watermark FROM s4_history_projection_progress WHERE tenant_id=? AND symbol_id=? ORDER BY generation DESC LIMIT 1" + currentRead(), Long.class, window.tenant, window.symbol))
            result.add(0, initial, "projection_before_initial");
        // A new native session anchor (or Monday-week anchor) can re-bucket controls outside this page.
        // Without a reviewed anchor migration, fail closed for historically protected instruments.
        if (!window.continuous && !minutes || "1w".equals(window.period)) {
            List<Long> protectedInstruments = store.db.queryForList("SELECT s.id FROM trading_symbol s WHERE s.tenant_id=? AND s.id=? AND ("
                + "EXISTS(SELECT 1 FROM market_control_task p WHERE p.tenant_id=s.tenant_id AND p.symbol_id=s.id LIMIT 1) OR "
                + "EXISTS(SELECT 1 FROM market_mixed_minute p WHERE p.tenant_id=s.tenant_id AND p.symbol_id=s.id LIMIT 1) OR "
                + "EXISTS(SELECT 1 FROM market_legacy_minute_snapshot p WHERE p.tenant_id=s.tenant_id AND p.symbol_id=s.id LIMIT 1) OR "
                + "EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=s.tenant_id AND p.symbol_id=s.id LIMIT 1) OR "
                + "EXISTS(SELECT 1 FROM market_simulation_source_candle p WHERE p.tenant_id=s.tenant_id AND p.symbol_id=s.id LIMIT 1)) LIMIT 1" + currentRead(),
                Long.class, window.tenant, window.symbol);
            if (!protectedInstruments.isEmpty()) result.add(window.from, Long.MAX_VALUE, "protected_source_period_anchor");
        }
        if ("Yahoo".equals(window.provider) && !MarketQuoteSource.nativeYahooHistoryPeriod(window.period))
            result.add(window.from, Long.MAX_VALUE, "unsupported_source_period");
        if ("Yahoo".equals(window.provider) && RandomMarketPath.duration(window.period) >= 86400000L
                || window.projection && minutes && !window.continuous)
            result.add(window.from, Long.MAX_VALUE, "source_calendar_unverified"); // Do not widen the continuous SOURCE prefix across sessions.
        if (!Arrays.asList("Binance", "OKX", "Yahoo").contains(window.provider)) result.add(window.from, Long.MAX_VALUE, "unsupported_source");
        return result;
    }
    /** Network has already finished. Recheck route, all protections and existence under real writer authority. */
    Receipt insert(Window window, Map<String,Object> response, long received) {
        TenantContext.require(window.tenant);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("History repair requires its own writer transaction");
        if (!Integer.valueOf(200).equals(response.get("ret")) || !(response.get("data") instanceof Map)
                || !(((Map<?,?>)response.get("data")).get("kline_list") instanceof List)) throw new MarketHttp.Failure("invalid_history_response", 0);
        List<Map<String,Object>> supplied = ControlHistoryStore.rows(response);
        if (supplied.size() > window.limit) throw new MarketHttp.Failure("history_window_too_large", 0);
        TreeMap<Long,Map<String,Object>> candidates = new TreeMap<>();
        List<Map<String,Object>> invalid = new ArrayList<>();
        Set<Long> conflicts = new HashSet<>();
        for (Map<String,Object> row : supplied) {
            long at = ControlHistoryStore.time(row);
            if (at < window.from || at > window.to || end(window.period, at) > received) continue;
            if (row.containsKey("historySource") && !window.identity.equals(row.get("historySource"))
                    || row.containsKey("source") && !Arrays.asList(window.provider, window.configuredSource, "External").contains(row.get("source"))) {
                invalid.add(gap(at, end(window.period, at) - 1, "source_conflict", false)); continue;
            }
            boolean aligned = Arrays.asList("Binance", "OKX").contains(window.provider) ? start(window.period, at) == at : at % 60000 == 0;
            if (!aligned || !valid(row) || Boolean.TRUE.equals(row.get("partial"))) {
                invalid.add(gap(at, end(window.period, at) - 1, "invalid_upstream_candle", false)); continue;
            }
            Map<String,Object> copy = new LinkedHashMap<>(row); copy.put("timestamp", at); copy.put("historySource", window.identity); copy.put("historyOnly", true);
            Map<String,Object> prior = candidates.putIfAbsent(at, copy);
            if (prior != null && !prior.equals(copy)) conflicts.add(at);
        }
        for (Long at : conflicts) { candidates.remove(at); invalid.add(gap(at, end(window.period, at) - 1, "upstream_conflict", false)); }
        // Encode outside locks; no provider data can silently rewrite an existing row or its reception time.
        Map<Long,String> encoded = new TreeMap<>(); for (Map.Entry<Long,Map<String,Object>> row : candidates.entrySet()) encoded.put(row.getKey(), store.encode(row.getValue()));
        return store.locked(window.symbol, () -> {
            Map<String,Object> route = store.db.queryForMap("SELECT symbol,alltick_symbol,market_source,source_category,row_version,random_market_enabled,random_market_started_at FROM trading_symbol WHERE tenant_id=? AND id=? FOR UPDATE", window.tenant, window.symbol);
            String code = route.get("alltick_symbol") == null || route.get("alltick_symbol").toString().isEmpty() ? String.valueOf(route.get("symbol")) : String.valueOf(route.get("alltick_symbol"));
            if (!Objects.equals(code, window.code) || !Objects.equals(route.get("market_source"), window.configuredSource)
                    || !Objects.equals(route.get("source_category"), window.category) || ((Number)route.get("row_version")).longValue() != window.version)
                throw new IllegalStateException("History source route/version changed");
            Protection protection = protection(window);
            if (Boolean.TRUE.equals(route.get("random_market_enabled")) || route.get("random_market_enabled") instanceof Number && ((Number)route.get("random_market_enabled")).intValue() != 0) {
                long session = route.get("random_market_started_at") == null ? 0 : ((Number)route.get("random_market_started_at")).longValue();
                protection.add(session, Long.MAX_VALUE, "simulation_history");
            }
            Map<Long,Map<String,Object>> old = existing(window);
            if (!encoded.isEmpty()) {
                List<Object> arguments = new ArrayList<>(Arrays.asList(window.tenant, window.symbol, window.period)); arguments.addAll(encoded.keySet());
                for (Map<String,Object> row : store.db.queryForList("SELECT candle_at,body,received_at FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period=? AND candle_at IN ("
                        + String.join(",", Collections.nCopies(encoded.size(), "?")) + ")" + currentRead(), arguments.toArray()))
                    old.put(((Number)row.get("candle_at")).longValue(), row);
            }
            List<Map<String,Object>> gaps = new ArrayList<>(invalid);
            int inserted = 0; long dirtyFrom = Long.MAX_VALUE, dirtyTo = Long.MIN_VALUE;
            for (Map.Entry<Long,String> row : encoded.entrySet()) {
                long at = row.getKey(); Map<String,Object> prior = old.get(at);
                String reason = prior == null ? protection.reason(at, end(window.period, at)) : existingProblem(window, prior);
                if (prior != null || reason != null) {
                    if (reason != null) gaps.add(gap(at, end(window.period, at) - 1, reason, false));
                    continue;
                }
                store.db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES(?,?,?,?,?,?)", window.tenant, window.symbol, window.period, at, row.getValue(), received);
                inserted++; old.put(at, Collections.singletonMap("body", row.getValue()));
                if ("1m".equals(window.period)) { dirtyFrom = Math.min(dirtyFrom, at); dirtyTo = Math.max(dirtyTo, at); }
            }
            if (dirtyFrom != Long.MAX_VALUE) store.runtime.sourceChanged(window.symbol, dirtyFrom, dirtyTo);
            if (window.continuous) for (long at = window.from; at <= window.to; at = end(window.period, at)) {
                final long missing = at;
                if (!old.containsKey(at) && gaps.stream().noneMatch(g -> ((Number)g.get("from")).longValue() == missing)) {
                    String reason = protection.reason(at, end(window.period, at));
                    gaps.add(gap(at, end(window.period, at) - 1, reason == null ? "upstream_no_data" : reason, false));
                }
            }
            if (!window.continuous && candidates.isEmpty() && gaps.isEmpty()) gaps.add(gap(window.from, window.to, "upstream_no_data", false));
            return new Receipt(gaps, inserted);
        });
    }
}
