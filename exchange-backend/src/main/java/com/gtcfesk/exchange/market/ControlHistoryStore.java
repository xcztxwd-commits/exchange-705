package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

/** Durable source snapshots and mixed minutes. The symbol row serializes all writers across processes. */
@Service
@DependsOn("entityManagerFactory")
public class ControlHistoryStore {
    final JdbcTemplate db;
    static long tenant() { return TenantContext.requireTenantId(); }
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final Map<String, TargetControlPlan> plans = Collections.synchronizedMap(new LinkedHashMap<String, TargetControlPlan>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, TargetControlPlan> oldest) { return size() > 8; }
    });
    private final ThreadLocal<Map<String,TargetControlPlan>> preparedPlans = new ThreadLocal<>();
    /** Pin immutable plans for one operation so LRU eviction cannot cause decoding under its symbol locks. */
    <T> T withPlans(Collection<Long> symbols, Supplier<T> operation) {
        if (preparedPlans.get() != null) return operation.get();
        Map<String,TargetControlPlan> prepared = new HashMap<>();
        for (Long symbol : symbols) {
            List<Map<String,Object>> tasks = db.queryForList("SELECT id,algorithm_version FROM market_control_task WHERE tenant_id=" + tenant()
                + " AND symbol_id=? ORDER BY started_at DESC,id DESC LIMIT 1", symbol);
            if (!tasks.isEmpty() && ((Number)tasks.get(0).get("algorithm_version")).intValue() >= 3) {
                String id = (String)tasks.get(0).get("id"); prepared.put(tenant() + ":" + id, plan(id));
            }
        }
        preparedPlans.set(prepared);
        try { return operation.get(); } finally { preparedPlans.remove(); }
    }
    void rememberPlan(String taskId, TargetControlPlan plan) {
        String key = tenant() + ":" + taskId;
        if (preparedPlans.get() != null) preparedPlans.get().put(key, plan);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { plans.put(key, plan); }
            });
        } else plans.put(key, plan);
    }
    public ControlHistoryStore(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db; transactions = new TransactionTemplate(manager);
    }
    @PostConstruct public void migrate() {
        // Schema-only validation; runtime must never create legacy unscoped private tables.
        db.queryForList("SELECT tenant_id FROM market_control_task WHERE 1=0");
    }
    <T> T locked(long symbol, Supplier<T> operation) {
        return transactions.execute(status -> {
            db.queryForObject("SELECT id FROM trading_symbol WHERE tenant_id=" + tenant() + " AND id=? FOR UPDATE", Long.class, symbol);
            return operation.get();
        });
    }
    <T> T transaction(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
    static final class EncodedPlan {
        final String parameters, prices, summary, checksum;
        EncodedPlan(String parameters, String prices, String summary, String checksum) {
            this.parameters = parameters; this.prices = prices; this.summary = summary; this.checksum = checksum;
        }
    }
    EncodedPlan encodePlan(TargetControlPlan plan) {
        try {
            return new EncodedPlan(json.writeValueAsString(plan.snapshot()), json.writeValueAsString(plan.prices()),
                json.writeValueAsString(plan.summary()), plan.checksum());
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Cannot serialize control plan", invalid); }
    }
    void savePlan(String taskId, long seed, EncodedPlan plan) {
        db.update("INSERT INTO market_control_plan(tenant_id,task_id,seed,parameters_json,prices_json,summary_json,checksum) VALUES(" + tenant() + ",?,?,?,?,?,?)",
            taskId, seed, plan.parameters, plan.prices, plan.summary, plan.checksum);
    }
    TargetControlPlan plan(String taskId) {
        String key = tenant() + ":" + taskId;
        TargetControlPlan pinned = preparedPlans.get() == null ? null : preparedPlans.get().get(key);
        if (pinned != null) return pinned;
        TargetControlPlan cached = plans.get(key);
        if (cached != null) return cached;
        if (preparedPlans.get() != null) throw new BalancedControlPlan.Failure("START_BASIS_CHANGED", "并发任务已变化，请重试以加载已提交计划");
        List<Map<String, Object>> rows = db.queryForList("SELECT parameters_json,prices_json,checksum FROM market_control_plan WHERE tenant_id=" + tenant() + " AND task_id=?", taskId);
        if (rows.size() != 1) throw new BalancedControlPlan.Failure("PLAN_CORRUPTED", "目标轨迹计划缺失");
        try {
            Map<String, Object> row = rows.get(0), parameters = decode((String) row.get("parameters_json"));
            int precision = ((Number) parameters.get("precision")).intValue();
            BigDecimal start = new BigDecimal((String) parameters.get("start")).movePointLeft(precision);
            BigDecimal target = new BigDecimal((String) parameters.get("target")).movePointLeft(precision);
            int duration = ((Number) parameters.get("duration")).intValue(), intensity = ((Number) parameters.get("intensity")).intValue();
            BigDecimal ratio = new BigDecimal((String) parameters.get("ratio"));
            List<String> prices = json.readValue((String) row.get("prices_json"), new TypeReference<List<String>>() {});
            TargetControlPlan plan;
            int version = ((Number) parameters.get("algorithmVersion")).intValue();
            if (version == BalancedControlPlan.VERSION) {
                BalancedControlPlan.Parameters p = new BalancedControlPlan.Parameters(start, target, duration, precision, intensity, ratio);
                plan = BalancedControlPlan.restore(p, prices);
            } else if (version == StabilizedControlPlan.VERSION) {
                TargetControlOptions options = new TargetControlOptions();
                options.setStepFormula((String) parameters.get("stepFormula")); options.setDeviationBandMode((String) parameters.get("deviationBandMode"));
                options.setDeviationBandPercent(new BigDecimal((String) parameters.get("deviationBandPercent")));
                TargetControlSettings settings = new TargetControlSettings(start, target, duration, precision, intensity, options);
                plan = StabilizedControlPlan.restore(new StabilizedControlPlan.Parameters(start, target, duration, precision, intensity, ratio, settings), prices);
            } else throw new IllegalStateException("Unsupported control algorithm " + version);
            if (!plan.snapshot().equals(parameters)) throw new IllegalStateException("Parameter snapshot mismatch");
            if (!plan.checksum().equals(row.get("checksum"))) throw new IllegalStateException("Checksum mismatch");
            plans.put(key, plan); return plan;
        } catch (Exception invalid) { throw new BalancedControlPlan.Failure("PLAN_CORRUPTED", "目标轨迹计划损坏：" + invalid.getMessage()); }
    }
    String encode(Map<String, Object> row) {
        try { return json.writeValueAsString(row); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize market history", e); }
    }
    Map<String, Object> decode(String row) {
        try { return json.readValue(row, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid persisted market history", e); }
    }
    static long time(Map<String, Object> row) { return RandomMarketPath.timestamp(row); }
    /** Providers may append a quote-time snapshot to OHLC pages. It is not a completed period. */
    static boolean periodCandle(Map<String,Object> row, String period) {
        long width = RandomMarketPath.duration(period);
        long alignment = width < 3600000 ? width : 60000;
        return time(row) > 0 && Math.floorMod(time(row), alignment) == 0;
    }
    static BigDecimal number(Object value) { return new BigDecimal(value.toString()); }
    static final class PricePoint {
        final long generatedAt;
        final BigDecimal price;
        PricePoint(long generatedAt, BigDecimal price) { this.generatedAt = generatedAt; this.price = price; }
    }
    /** Backfill uses bounded SQL batches and one OHLC write per minute, not several queries per second. */
    void generatedPoints(String task, long symbol, List<PricePoint> points) {
        for (int offset = 0; offset < points.size(); offset += 500) {
            int count = Math.min(500, points.size() - offset);
            String sql = "INSERT INTO market_control_sample(tenant_id,task_id,generated_at,price) VALUES "
                + String.join(",", Collections.nCopies(count, "(" + tenant() + ",?,?,?)"));
            Object[] arguments = new Object[count * 3];
            for (int i = 0; i < count; i++) {
                PricePoint point = points.get(offset + i);
                arguments[i * 3] = task; arguments[i * 3 + 1] = point.generatedAt; arguments[i * 3 + 2] = point.price;
            }
            db.update(sql, arguments);
        }
        long minute = -1, last = -1;
        Map<String, Object> bar = null;
        for (PricePoint point : points) {
            long nextMinute = point.generatedAt / 60000 * 60000;
            if (nextMinute != minute) {
                if (bar != null) saveMinute(symbol, minute, bar, last);
                minute = nextMinute;
                List<Map<String, Object>> saved = mixed(symbol, minute, minute);
                bar = saved.isEmpty() ? new LinkedHashMap<>() : saved.get(0);
                last = saved.isEmpty() ? -1 : db.queryForObject("SELECT last_event FROM market_mixed_minute WHERE tenant_id=" + tenant() + " AND symbol_id=? AND minute_at=?", Long.class, symbol, minute);
            }
            if (point.generatedAt <= last) continue;
            addPrice(bar, minute, point.price); last = point.generatedAt;
        }
        if (bar != null) saveMinute(symbol, minute, bar, last);
    }
    private static void addPrice(Map<String, Object> bar, long minute, BigDecimal price) {
        if (bar.isEmpty()) {
            bar.put("timestamp", minute); bar.put("open_price", price); bar.put("high_price", price); bar.put("low_price", price);
            bar.put("volume", 0); bar.put("partial", true); bar.put("controlled", true);
        } else {
            bar.put("high_price", number(bar.get("high_price")).max(price));
            bar.put("low_price", number(bar.get("low_price")).min(price));
        }
        bar.put("close_price", price);
    }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> result) {
        return (List<Map<String, Object>>) ((Map<?, ?>) result.get("data")).get("kline_list");
    }
    public void sourceCandles(long symbol, String period, List<Map<String, Object>> rows, long now) {
        sourceCandles(Collections.singletonList(symbol), period, rows, now);
    }
    /** Encode once before acquiring any alias lock; every alias/chunk still commits together. */
    public void sourceCandles(List<Long> symbols, String period, List<Map<String, Object>> rows, long now) {
        long owner = tenant();
        List<Object[]> encoded = new ArrayList<>();
        for (Map<String,Object> row : rows) {
            long at = time(row);
            Map<String,Object> copy = new LinkedHashMap<>(row); copy.put("timestamp", at);
            encoded.add(new Object[]{at, encode(copy)});
        }
        List<Long> ordered = new ArrayList<>(new TreeSet<>(symbols));
        transaction(() -> {
            TenantContext.require(owner);
            for (Long symbol : ordered) locked(symbol, () -> {
                for (int offset = 0; offset < encoded.size(); offset += 500) {
                    int count = Math.min(500, encoded.size() - offset);
                    Object[] arguments = new Object[count * 5];
                    for (int i = 0; i < count; i++) {
                        Object[] row = encoded.get(offset + i);
                        arguments[i * 5] = symbol; arguments[i * 5 + 1] = period;
                        arguments[i * 5 + 2] = row[0]; arguments[i * 5 + 3] = row[1]; arguments[i * 5 + 4] = now;
                    }
                    db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES "
                        + String.join(",", Collections.nCopies(count, "(" + tenant() + ",?,?,?,?,?)"))
                        + " ON DUPLICATE KEY UPDATE body=VALUES(body),received_at=VALUES(received_at)", arguments);
                }
                return null;
            });
            return null;
        });
    }
    List<Map<String, Object>> candles(long symbol, String period, long from, long to) {
        List<Map<String,Object>> rows = db.query("SELECT body FROM market_source_candle WHERE tenant_id=" + tenant() + " AND symbol_id=? AND period=? AND candle_at>=? AND candle_at<=? ORDER BY candle_at",
            (rs, n) -> decode(rs.getString(1)), symbol, period, from, to);
        rows.removeIf(row -> !periodCandle(row, period)); return rows;
    }
    List<Map<String, Object>> mixed(long symbol, long from, long to) {
        return db.query("SELECT body FROM market_mixed_minute WHERE tenant_id=" + tenant() + " AND symbol_id=? AND minute_at>=? AND minute_at<=? ORDER BY minute_at",
            (rs, n) -> decode(rs.getString(1)), symbol, from, to);
    }
    void captureLegacyMinute(long symbol, long now) {
        long minute = now/60000*60000;
        Integer modern = db.queryForObject("SELECT COUNT(*) FROM market_control_sample s JOIN market_control_task t ON t.tenant_id=s.tenant_id AND t.id=s.task_id JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? AND s.generated_at>=? AND s.generated_at<?", Integer.class, symbol, minute, minute+60000);
        if (modern == 0) db.update("INSERT INTO market_legacy_minute_snapshot(tenant_id,symbol_id,minute_at,body,last_event) SELECT " + tenant() + ",symbol_id,minute_at,body,last_event FROM market_mixed_minute WHERE tenant_id=" + tenant() + " AND symbol_id=? AND minute_at=? ON DUPLICATE KEY UPDATE body=VALUES(body),last_event=VALUES(last_event)", symbol, minute);
    }
    /** Event-level visibility; batch reads avoid one database query per chart minute. */
    List<Map<String,Object>> visibleMixed(long symbol, long from, long to) {
        List<Map<String,Object>> original = mixed(symbol, from, to);
        if (original.isEmpty()) return original;
        long first = time(original.get(0)), end = time(original.get(original.size()-1)) + 60000;
        Set<Long> modern = new HashSet<>(db.queryForList("SELECT DISTINCT FLOOR(s.generated_at/60000)*60000 FROM market_control_sample s JOIN market_control_task t ON t.tenant_id=s.tenant_id AND t.id=s.task_id JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? AND s.generated_at>=? AND s.generated_at<?", Long.class, symbol, first, end));
        if (modern.isEmpty()) return original;
        Map<Long,List<Map<String,Object>>> events = new HashMap<>();
        Map<Long,Map<String,Object>> prefixes = new HashMap<>();
        for (Map<String,Object> prefix : db.queryForList("SELECT minute_at,body,last_event FROM market_legacy_minute_snapshot WHERE tenant_id=" + tenant() + " AND symbol_id=? AND minute_at>=? AND minute_at<?", symbol, first, end)) {
            long at = ((Number)prefix.get("minute_at")).longValue(); prefixes.put(at,prefix); events.put(at,new ArrayList<>());
        }
        List<Map<String,Object>> samples = db.queryForList("SELECT s.generated_at,s.price FROM market_control_sample s JOIN market_control_task t ON t.tenant_id=s.tenant_id AND t.id=s.task_id LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id LEFT JOIN market_control_publication p ON p.tenant_id=t.tenant_id AND p.task_id=t.id WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? AND s.generated_at>=? AND s.generated_at<? AND (f.task_id IS NULL OR f.state<>'SOURCE' OR (s.generated_at>=p.from_at AND s.generated_at<=p.to_at)) ORDER BY s.generated_at,t.id", symbol, first, end);
        for (Map<String,Object> sample : samples) events.computeIfAbsent(((Number)sample.get("generated_at")).longValue()/60000*60000, ignored -> new ArrayList<>()).add(sample);
        // Retain the prices actually displayed under manual rules, not today's offset or raw ticks.
        Set<Long> manualTimes = new HashSet<>();
        for (Map<String,Object> minute : original) if (modern.contains(time(minute))) {
            for (Map<String,Object> point : manualPoints(minute)) {
                manualTimes.add(((Number)point.get("generated_at")).longValue());
                events.computeIfAbsent(time(minute), ignored -> new ArrayList<>()).add(point);
            }
        }
        // Filter both UNION branches before materialization; the unbounded event history grows continuously.
        String windowEvents = "SELECT symbol_id,received_at,price,event_sequence FROM market_source_event WHERE tenant_id=" + tenant() + " AND symbol_id=? AND received_at>=? AND received_at<? UNION ALL "
            + "SELECT t.symbol_id,t.received_at,t.price,0 AS event_sequence FROM market_source_tick t WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? AND t.received_at>=? AND t.received_at<? "
            + "AND NOT EXISTS (SELECT 1 FROM market_source_event e WHERE e.tenant_id=t.tenant_id AND e.symbol_id=t.symbol_id AND e.source_time=t.source_time AND e.received_at=t.received_at AND e.price=t.price)";
        List<Map<String,Object>> ticks = db.queryForList("SELECT e.received_at AS generated_at,e.price,e.event_sequence FROM (" + windowEvents + ") e WHERE NOT EXISTS (SELECT 1 FROM market_control_task t LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id LEFT JOIN market_control_publication p ON p.tenant_id=t.tenant_id AND p.task_id=t.id WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=e.symbol_id AND e.received_at>=t.started_at AND e.received_at<=COALESCE(f.finished_at, CASE WHEN f.task_id IS NOT NULL THEN ? ELSE t.ended_at END) AND (f.task_id IS NULL OR f.state<>'SOURCE' OR (e.received_at>=p.from_at AND e.received_at<=p.to_at))) ORDER BY e.received_at,e.event_sequence", symbol, first, end, symbol, first, end, Long.MAX_VALUE);
        for (Map<String,Object> tick : ticks) {
            List<Map<String,Object>> minute = events.get(((Number)tick.get("generated_at")).longValue()/60000*60000);
            if (minute != null && !manualTimes.contains(((Number)tick.get("generated_at")).longValue())) minute.add(0, tick);
        }
        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> minute : original) {
            long at = time(minute);
            if (!modern.contains(at)) { result.add(minute); continue; }
            List<Map<String,Object>> points = events.get(at);
            if (points == null) continue; // Preserve original candles in the caller's source map.
            points.sort(Comparator.<Map<String,Object>>comparingLong(row -> ((Number)row.get("generated_at")).longValue())
                .thenComparingLong(row -> row.get("event_sequence") instanceof Number ? ((Number)row.get("event_sequence")).longValue() : Long.MAX_VALUE));
            Map<String,Object> prefix = prefixes.get(at);
            Map<String,Object> bar = prefix == null ? new LinkedHashMap<>() : decode((String)prefix.get("body"));
            long prefixEnd = prefix == null ? Long.MIN_VALUE : ((Number)prefix.get("last_event")).longValue();
            for (Map<String,Object> point : points) if (((Number)point.get("generated_at")).longValue() > prefixEnd) addPrice(bar, at, number(point.get("price")));
            bar.put("sourceCoverage", "observed_events_only"); bar.put("partial", true);
            result.add(bar);
        }
        return result;
    }
    /** Freeze only information actually known at activation, never a subsequently downloaded OHLC. */
    void freeze(long symbol, long now) {
        long minute = now / 60000 * 60000;
        if (!mixed(symbol, minute, minute).isEmpty()) return;
        List<Map<String, Object>> source = candles(symbol, "1m", minute, minute);
        long received = minute - 1;
        if (!source.isEmpty()) {
            Map<String, Object> bar = new LinkedHashMap<>(source.get(0));
            bar.put("controlled", true); bar.put("snapshotAt", now); bar.put("partial", true);
            received = storeSnapshotTime(symbol, minute);
            saveMinute(symbol, minute, bar, Math.min(now - 1, received));
        }
        List<Map<String, Object>> ticks = db.queryForList("SELECT received_at,price FROM (" + sourceEvents("symbol_id=? AND source_time>=? AND source_time<? AND received_at>? AND received_at<=?", false) + ") ticks ORDER BY received_at,event_sequence",
            symbol, minute, minute + 60000, received, now, symbol, minute, minute + 60000, received, now);
        for (Map<String, Object> tick : ticks) point(symbol, ((Number) tick.get("received_at")).longValue(), number(tick.get("price")), true);
    }
    private long storeSnapshotTime(long symbol, long minute) {
        return db.queryForObject("SELECT received_at FROM market_source_candle WHERE tenant_id=" + tenant() + " AND symbol_id=? AND period='1m' AND candle_at=?", Long.class, symbol, minute);
    }
    void point(long symbol, long time, BigDecimal price, boolean controlled) {
        point(symbol, time, price, controlled, false);
    }
    void manualPoint(long symbol, long time, BigDecimal price) {
        point(symbol, time, price, true, true);
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> manualPoints(Map<String,Object> minute) {
        return (List<Map<String,Object>>) minute.getOrDefault("manualPoints", Collections.emptyList());
    }
    private void point(long symbol, long time, BigDecimal price, boolean controlled, boolean manual) {
        long minute = time / 60000 * 60000;
        List<Map<String, Object>> existing = mixed(symbol, minute, minute);
        if (existing.isEmpty() && !controlled) return;
        Map<String, Object> bar;
        if (existing.isEmpty()) {
            bar = new LinkedHashMap<>();
        } else {
            long last = db.queryForObject("SELECT last_event FROM market_mixed_minute WHERE tenant_id=" + tenant() + " AND symbol_id=? AND minute_at=?", Long.class, symbol, minute);
            if (time < last) return;
            bar = existing.get(0);
        }
        addPrice(bar, minute, price);
        if (manual) {
            List<Map<String,Object>> points = new ArrayList<>(manualPoints(bar));
            Map<String,Object> point = new LinkedHashMap<>(); point.put("generated_at", time); point.put("price", price);
            points.add(point); bar.put("manualPoints", points);
        }
        saveMinute(symbol, minute, bar, time);
    }
    private void saveMinute(long symbol, long minute, Map<String, Object> bar, long last) {
        db.update("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(" + tenant() + ",?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE body=VALUES(body),last_event=VALUES(last_event)", symbol, minute, encode(bar), last);
    }
    /** The same predicate/arguments apply to both branches, before UNION materialization. */
    static String sourceEvents(String predicate, boolean latest) {
        String tail = latest ? " ORDER BY source_time DESC,received_at DESC,event_sequence DESC LIMIT 1" : "";
        // Bulk restores can leave tiny cardinalities until asynchronous stats refresh. Keep MySQL inside the bounded index.
        // MySQL executes the conditional comment; H2 differential fixtures ignore it. No predicates or tie order change.
        String events = "SELECT symbol_id,source_time,received_at,price,event_sequence FROM market_source_event /*! FORCE INDEX (source_event_time) */ WHERE tenant_id=" + tenant() + " AND " + predicate + tail;
        String ticks = "SELECT t.symbol_id,t.source_time,t.received_at,t.price,0 AS event_sequence FROM market_source_tick t WHERE t.tenant_id=" + tenant() + " AND " + predicate
            + " AND NOT EXISTS (SELECT 1 FROM market_source_event e WHERE e.tenant_id=t.tenant_id AND e.symbol_id=t.symbol_id AND e.source_time=t.source_time AND e.received_at=t.received_at AND e.price=t.price)" + tail;
        return latest ? "(" + events + ") UNION ALL (" + ticks + ")" : events + " UNION ALL " + ticks;
    }
    boolean quote(long symbol, Map<String, Object> quote, long receivedAt) {
        long time = QuoteState.time(quote.get("timestamp"));
        String eventId = String.valueOf(quote.getOrDefault("eventId", UUID.randomUUID().toString()));
        if (db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=" + tenant() + " AND symbol_id=? AND event_id=?", Integer.class, symbol, eventId) > 0) return false;
        db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(" + tenant() + ",?,?,?,?,?)",
            eventId, symbol, time, receivedAt, quote.get("price"));
        db.update("INSERT INTO market_source_quote(tenant_id,symbol_id,price,source_time) VALUES(" + tenant() + ",?,?,?) "
            + "ON DUPLICATE KEY UPDATE price=CASE WHEN source_time<=VALUES(source_time) THEN VALUES(price) ELSE price END,source_time=GREATEST(source_time,VALUES(source_time))",
            symbol, quote.get("price"), time);
        db.update("INSERT INTO market_source_tick(tenant_id,symbol_id,source_time,received_at,price) VALUES(" + tenant() + ",?,?,?,?) ON DUPLICATE KEY UPDATE source_time=VALUES(source_time)",
            symbol, time, receivedAt, quote.get("price"));
        return true;
    }
    Map<String, Object> lastQuote(long symbol) {
        List<Map<String, Object>> rows = db.query("SELECT price,source_time FROM market_source_quote WHERE tenant_id=" + tenant() + " AND symbol_id=?", (rs, n) -> {
            Map<String, Object> row = new HashMap<>(); row.put("price", rs.getBigDecimal(1)); row.put("timestamp", rs.getLong(2)); return row;
        }, symbol);
        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }
    Map<String, Object> lastClose(long symbol, long now) {
        List<Map<String, Object>> rows = db.query("SELECT body,period,received_at FROM market_source_candle WHERE tenant_id=" + tenant() + " AND symbol_id=? AND candle_at<? ORDER BY candle_at DESC LIMIT 100",
            (rs, n) -> { Map<String, Object> row = decode(rs.getString(1)); row.put("period", rs.getString(2)); row.put("receivedAt", rs.getLong(3)); return row; }, symbol, now);
        Map<String, Object> latest = Collections.emptyMap();
        long latestClose = 0;
        for (Map<String, Object> row : rows) {
            if (!periodCandle(row, String.valueOf(row.get("period")))) continue;
            long close = RandomMarketPath.periodEnd(String.valueOf(row.get("period")), time(row));
            // A snapshot fetched while the candle was open does not become a confirmed close merely because time passed.
            if (close <= now && QuoteState.time(row.get("receivedAt")) >= close && close > latestClose) { latest = row; latestClose = close; }
        }
        return latest;
    }
}
