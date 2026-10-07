package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonGenerator;
import java.nio.charset.StandardCharsets;
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
    final MarketRuntime runtime = new MarketRuntime(this);
    final HistoryOrdering historyOrdering = new HistoryOrdering(this);
    static long tenant() { return TenantContext.requireTenantId(); }
    private final TransactionTemplate transactions;
    private final TransactionTemplate reads, consumerReads;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(JsonGenerator.Feature.ESCAPE_NON_ASCII);
    final ControlPlanBudget budget=new ControlPlanBudget();
    private final ThreadLocal<Map<String,TargetControlPlan>> preparedPlans = new ThreadLocal<>();
    /** Pin immutable plans for one operation so LRU eviction cannot cause decoding under its symbol locks. */
    <T> T withPlans(Collection<Long> symbols, Supplier<T> operation) {
        if (preparedPlans.get() != null) return operation.get();
        Map<String,TargetControlPlan> prepared = new HashMap<>();
        List<ControlPlanBudget.Lease> leases=new ArrayList<>();
        try {
        for (Long symbol : symbols) {
            List<Map<String,Object>> tasks = db.queryForList("SELECT t.id,t.algorithm_version,t.duration_seconds,t.status,t.stop_at,f.state AS flow_state FROM market_control_task t LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id WHERE t.tenant_id=" + tenant()
                + " AND t.symbol_id=? ORDER BY t.started_at DESC,t.id DESC LIMIT 1", symbol);
            if (!tasks.isEmpty() && !"STOPPED".equals(tasks.get(0).get("status")) && !"SOURCE".equals(tasks.get(0).get("flow_state")) && (tasks.get(0).get("stop_at")==null || "RUNNING".equals(tasks.get(0).get("status"))) && ((Number)tasks.get(0).get("algorithm_version")).intValue() >= 3) {
                leases.add(budget.acquire(65536L+256L*(1+((Number)tasks.get(0).get("duration_seconds")).longValue())));
                String id = (String)tasks.get(0).get("id"); prepared.put(tenant() + ":" + id, plan(id));
            }
        }
        preparedPlans.set(prepared);
        return operation.get();
        } finally { preparedPlans.remove();for(ControlPlanBudget.Lease lease:leases)lease.close(); }
    }
    void rememberPlan(String taskId, TargetControlPlan plan) {
        if(preparedPlans.get()!=null) preparedPlans.get().put(tenant()+":"+taskId,plan);
    }
    public ControlHistoryStore(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db; transactions = new TransactionTemplate(manager);
        transactions.setTimeout(5); // JDBC statements share the physical transaction deadline, not a fresh timeout per call.
        reads = new TransactionTemplate(manager); reads.setReadOnly(true);
        reads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        consumerReads = new TransactionTemplate(manager);
        consumerReads.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        consumerReads.setReadOnly(true);
        consumerReads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @PostConstruct public void migrate() {
        // Schema-only validation; runtime must never create legacy unscoped private tables.
        db.queryForList("SELECT tenant_id FROM market_control_task WHERE 1=0");
        db.queryForList("SELECT history_pending_until,history_retry_at,history_error FROM market_control_flow WHERE 1=0");
        db.queryForList("SELECT source_input_revision,source_dirty_from,source_dirty_to FROM market_engine_runtime WHERE 1=0");
        if(Boolean.TRUE.equals(db.execute((java.sql.Connection c)->c.getMetaData().getDatabaseProductName().equals("MySQL"))))
            db.queryForList("SELECT input_revision FROM s4_history_projection_progress WHERE 1=0");
    }
    <T> T locked(long symbol, Supplier<T> operation) {
        return transactions.execute(status -> {
            long at=System.nanoTime();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCompletion(int completion) {
                    org.slf4j.LoggerFactory.getLogger(ControlHistoryStore.class).info("control_transaction tenant={} symbol={} traceId={} requestKey={} ms={} outcome={}",tenant(),symbol,org.slf4j.MDC.get("traceId"),org.slf4j.MDC.get("requestKey"),(System.nanoTime()-at)/1000000,completion==STATUS_COMMITTED?"COMMITTED":completion==STATUS_ROLLED_BACK?"ROLLED_BACK":"UNKNOWN");
                }
            });
            return runtime.locked(symbol, operation);
        });
    }
    <T> T transaction(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
    /** Pure readers share one committed view across all pages; must not run in a writer transaction. */
    <T> T readSnapshot(Supplier<T> operation) { return reads.execute(status -> operation.get()); }
    /** One HTTP response/price frame; no writer transaction may become a display read. */
    <T> T readConsumerSnapshot(Supplier<T> operation) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            requireConsumerSnapshot(db);
            return operation.get();
        }
        return consumerReads.execute(status -> { requireConsumerSnapshot(db); return operation.get(); });
    }
    static void requireConsumerSnapshot(JdbcTemplate db) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ).equals(
                    org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
            throw new IllegalStateException("Market consumer requires a read-only RR transaction");
        Boolean qualified = db.execute((java.sql.Connection connection) -> !connection.getAutoCommit()
            && (!"MySQL".equals(connection.getMetaData().getDatabaseProductName()) || connection.isReadOnly()) && connection.getTransactionIsolation() == java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        if (!Boolean.TRUE.equals(qualified)) throw new IllegalStateException("Market consumer JDBC boundary differs");
    }
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
                if (preparedPlans.get() != null) throw new BalancedControlPlan.Failure("START_BASIS_CHANGED", "并发任务已变化，请重试以加载已提交计划");
        List<Map<String, Object>> rows = db.queryForList("SELECT parameters_json,prices_json,checksum FROM market_control_plan WHERE tenant_id=" + tenant() + " AND task_id=?", taskId);
        if (rows.size() != 1) throw new BalancedControlPlan.Failure("PLAN_CORRUPTED", "目标轨迹计划缺失");
        return restorePlan(rows.get(0));
    }
    TargetControlPlan restorePlan(Map<String,Object> row) {
        try {
            Map<String, Object> parameters = decode((String) row.get("parameters_json"));
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
            return plan;
        } catch (Exception invalid) { throw new BalancedControlPlan.Failure("PLAN_CORRUPTED", "目标轨迹计划损坏：" + invalid.getMessage()); }
    }
    // ASCII storage keeps native latin1 command columns lossless without a schema rewrite.
    private static final String COMMAND_MESSAGE_PREFIX = "~mcc1~";
    static String encodeCommandMessage(String message) {
        if (message == null) return null;
        String stored = message;
        if (message.startsWith(COMMAND_MESSAGE_PREFIX) || message.chars().anyMatch(c -> c > 127)) {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            if (!message.equals(new String(bytes, StandardCharsets.UTF_8))) throw new IllegalArgumentException("Invalid command message UTF-16");
            stored = COMMAND_MESSAGE_PREFIX + Base64.getEncoder().encodeToString(bytes);
        }
        if (stored.length() > 255) throw new IllegalArgumentException("Command message exceeds persisted VARCHAR(255)");
        return stored;
    }
    static String decodeCommandMessage(String stored) {
        if (stored == null || !stored.startsWith(COMMAND_MESSAGE_PREFIX)) return stored;
        try {
            byte[] bytes = Base64.getDecoder().decode(stored.substring(COMMAND_MESSAGE_PREFIX.length()));
            String message = new String(bytes, StandardCharsets.UTF_8);
            // A malformed legacy prefix remains plain; never replace invalid bytes silently.
            return Arrays.equals(bytes, message.getBytes(StandardCharsets.UTF_8)) ? message : stored;
        } catch (IllegalArgumentException legacyPlain) { return stored; }
    }
    /** Immutable history request identity predates ASCII storage; preserve its exact hash bytes. */
    String encodeHistoryRequest(Map<String, Object> row) {
        try { return json.writer().without(JsonGenerator.Feature.ESCAPE_NON_ASCII).writeValueAsString(row); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize exact history request", e); }
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
        if(points.size()>runtime.sampleAllowance(symbol))throw new com.gtcfesk.exchange.common.BusinessException("ENGINE_BUDGET: 当前事务采样额度已耗尽");
        for (int offset = 0; offset < points.size(); offset += 500) {
            runtime.requireBudget();
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
        runtime.sampled(symbol,points.size());
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
                long dirtyFrom=Long.MAX_VALUE,dirtyTo=Long.MIN_VALUE;
                for (int offset = 0; offset < encoded.size(); offset += 500) {
                    int count = Math.min(500, encoded.size() - offset);
                    Object[] arguments = new Object[count * 5];
                    Map<Long,Map<String,Object>> prior=new HashMap<>();
                    if("1m".equals(period)) {
                        List<Object> lookup=new ArrayList<>(Arrays.asList(tenant(),symbol,period));
                        for(int i=0;i<count;i++) lookup.add(encoded.get(offset+i)[0]);
                        for(Map<String,Object> row:db.queryForList("SELECT candle_at,body,received_at FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period=? AND candle_at IN ("+String.join(",",Collections.nCopies(count,"?"))+") FOR UPDATE",lookup.toArray()))
                            prior.put(((Number)row.get("candle_at")).longValue(),row);
                    }
                    for (int i = 0; i < count; i++) {
                        Object[] row = encoded.get(offset + i);
                        if("1m".equals(period)) {
                            long at=((Number)row[0]).longValue();Map<String,Object> old=prior.get(at);
                            if(at>0 && at%60000==0 && (old==null || !Objects.equals(old.get("body"),row[1]) || ((Number)old.get("received_at")).longValue()!=now)) {
                                dirtyFrom=Math.min(dirtyFrom,at);dirtyTo=Math.max(dirtyTo,at);
                            }
                        }
                        arguments[i * 5] = symbol; arguments[i * 5 + 1] = period;
                        arguments[i * 5 + 2] = row[0]; arguments[i * 5 + 3] = row[1]; arguments[i * 5 + 4] = now;
                    }
                    db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES "
                        + String.join(",", Collections.nCopies(count, "(" + tenant() + ",?,?,?,?,?)"))
                        + " ON DUPLICATE KEY UPDATE body=VALUES(body),received_at=VALUES(received_at)", arguments);
                }
                if(dirtyFrom!=Long.MAX_VALUE) runtime.sourceChanged(symbol,dirtyFrom,dirtyTo);
                return null;
            });
            return null;
        });
    }
    static final int HISTORY_PAGE_SIZE = 500;
    List<Map<String, Object>> candles(long symbol, String period, long from, long to) {
        List<Map<String,Object>> result = new ArrayList<>();
        candlePages(symbol, period, from, to, result::addAll); return result;
    }
    /** Keyset pages retain complete requested history without materializing an unbounded JDBC result. */
    void candlePages(long symbol, String period, long from, long to, java.util.function.Consumer<List<Map<String,Object>>> consumer) {
        if (from > to) return;
        long owner = tenant(), next = from;
        while (true) {
            TenantContext.require(owner);
            List<Map<String,Object>> page = db.queryForList("SELECT candle_at,body FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period=? AND candle_at>=? AND candle_at<=? ORDER BY candle_at LIMIT ?",
                owner, symbol, period, next, to, HISTORY_PAGE_SIZE);
            List<Map<String,Object>> rows = new ArrayList<>(page.size());
            for (Map<String,Object> row : page) {
                Map<String,Object> candle = decode((String)row.get("body"));
                if (periodCandle(candle, period)) rows.add(candle);
            }
            if (!rows.isEmpty()) consumer.accept(rows);
            if (page.size() < HISTORY_PAGE_SIZE) return;
            long last = ((Number)page.get(page.size()-1).get("candle_at")).longValue();
            if (last == Long.MAX_VALUE) return;
            next = last + 1;
        }
    }
    List<Map<String, Object>> mixed(long symbol, long from, long to) {
        List<Map<String,Object>> result = new ArrayList<>();
        mixedPages(symbol, from, to, result::addAll); return result;
    }
    private void mixedPages(long symbol, long from, long to, java.util.function.Consumer<List<Map<String,Object>>> consumer) {
        if (from > to) return;
        long owner = tenant(), next = from;
        while (true) {
            TenantContext.require(owner);
            List<Map<String,Object>> page = db.queryForList("SELECT minute_at,body FROM market_mixed_minute WHERE tenant_id=? AND symbol_id=? AND minute_at>=? AND minute_at<=? ORDER BY minute_at LIMIT ?",
                owner, symbol, next, to, HISTORY_PAGE_SIZE);
            List<Map<String,Object>> rows = new ArrayList<>(page.size());
            for (Map<String,Object> row : page) rows.add(decode((String)row.get("body")));
            if (!rows.isEmpty()) consumer.accept(rows);
            if (page.size() < HISTORY_PAGE_SIZE) return;
            long last = ((Number)page.get(page.size()-1).get("minute_at")).longValue();
            if (last == Long.MAX_VALUE) return;
            next = last + 1;
        }
    }
    /** Read each publication once per bounded window group, not once per chart period/task. */
    Set<Long> publishedBuckets(long symbol, Map<Long,Long> bucketEnds) {
        Set<Long> result = new HashSet<>();
        if (bucketEnds.isEmpty()) return result;
        long owner = tenant();
        Iterator<Map.Entry<Long,Long>> pending = bucketEnds.entrySet().iterator();
        while (pending.hasNext()) {
            List<Map.Entry<Long,Long>> windows = new ArrayList<>(HISTORY_PAGE_SIZE);
            StringJoiner overlap = new StringJoiner(" OR ", "(", ")");
            List<Object> bounds = new ArrayList<>(Arrays.asList(owner, owner, symbol));
            while (pending.hasNext() && windows.size() < HISTORY_PAGE_SIZE) {
                Map.Entry<Long,Long> window = pending.next();
                if (window.getValue() <= window.getKey()) throw new IllegalArgumentException("Invalid publication bucket");
                windows.add(window); overlap.add("(p.from_at<? AND p.to_at>=?)");
                Collections.addAll(bounds, window.getValue(), window.getKey());
            }
            String after = null;
            while (true) {
                TenantContext.require(owner);
                List<Object> arguments = new ArrayList<>(bounds);
                if (after != null) arguments.add(after);
                arguments.add(HISTORY_PAGE_SIZE);
                List<Map<String,Object>> page = db.queryForList("SELECT p.task_id,p.from_at,p.to_at FROM market_control_publication p JOIN market_control_task t ON t.tenant_id=p.tenant_id AND t.id=p.task_id WHERE p.tenant_id=? AND t.tenant_id=? AND t.symbol_id=? AND " + overlap
                    + (after == null ? "" : " AND p.task_id>?") + " ORDER BY p.task_id LIMIT ?", arguments.toArray());
                for (Map<String,Object> publication : page) {
                    long from = ((Number)publication.get("from_at")).longValue(), to = ((Number)publication.get("to_at")).longValue();
                    for (Map.Entry<Long,Long> window : windows) if (from < window.getValue() && to >= window.getKey()) result.add(window.getKey());
                }
                boolean complete = true;
                for (Map.Entry<Long,Long> window : windows) if (!result.contains(window.getKey())) { complete = false; break; }
                if (complete || page.size() < HISTORY_PAGE_SIZE) break;
                after = (String)page.get(page.size()-1).get("task_id");
            }
        }
        return result;
    }
    void captureLegacyMinute(long symbol, long now) {
        long minute = now/60000*60000;
        Integer modern = db.queryForObject("SELECT COUNT(*) FROM market_control_sample s JOIN market_control_task t ON t.tenant_id=s.tenant_id AND t.id=s.task_id JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? AND s.generated_at>=? AND s.generated_at<?", Integer.class, symbol, minute, minute+60000);
        if (modern == 0) db.update("INSERT INTO market_legacy_minute_snapshot(tenant_id,symbol_id,minute_at,body,last_event) SELECT " + tenant() + ",symbol_id,minute_at,body,last_event FROM market_mixed_minute WHERE tenant_id=" + tenant() + " AND symbol_id=? AND minute_at=? ON DUPLICATE KEY UPDATE body=VALUES(body),last_event=VALUES(last_event)", symbol, minute);
    }
    /** Compatibility collector; chart readers use pages so memory follows returned buckets, not all seconds. */
    List<Map<String,Object>> visibleMixed(long symbol, long from, long to) {
        List<Map<String,Object>> result = new ArrayList<>();
        visibleMixedPages(symbol, from, to, result::addAll); return result;
    }
    void visibleMixedPages(long symbol, long from, long to, java.util.function.Consumer<List<Map<String,Object>>> consumer) {
        mixedPages(symbol, from, to, page -> {
            List<Map<String,Object>> visible = visibleMixedPage(symbol, page);
            if (!visible.isEmpty()) consumer.accept(visible);
        });
    }
    List<Map<String,Object>> visibleMixedAt(long symbol, List<Long> minuteKeys) {
        if (minuteKeys.size() > HISTORY_PAGE_SIZE) throw new IllegalArgumentException("Mixed-minute page exceeds 500 rows");
        if (minuteKeys.isEmpty()) return Collections.emptyList();
        List<Object> arguments = new ArrayList<>(Arrays.asList(tenant(), symbol)); arguments.addAll(minuteKeys); arguments.add(HISTORY_PAGE_SIZE);
        List<Map<String,Object>> rows = db.queryForList("SELECT body FROM market_mixed_minute WHERE tenant_id=? AND symbol_id=? AND minute_at IN ("
            + String.join(",", Collections.nCopies(minuteKeys.size(), "?")) + ") ORDER BY minute_at LIMIT ?", arguments.toArray());
        List<Map<String,Object>> original = new ArrayList<>(rows.size());
        for (Map<String,Object> row : rows) original.add(decode((String)row.get("body")));
        return original.isEmpty() ? original : visibleMixedPage(symbol, original);
    }
    /** Sparse pages exclude every unrequested gap before materialization in each table/UNION branch. */
    private static String minuteWindows(String column, Collection<Long> minutes, List<Object> arguments) {
        StringJoiner clauses = new StringJoiner(" OR ", "(", ")");
        Long from = null, last = null;
        for (Long minute : new TreeSet<>(minutes)) {
            if (from == null) from = minute;
            else if (last > Long.MAX_VALUE - 60000 || minute != last + 60000) {
                clauses.add("(" + column + ">=? AND " + column + "<?)"); Collections.addAll(arguments, from, last > Long.MAX_VALUE - 60000 ? Long.MAX_VALUE : last + 60000); from = minute;
            }
            last = minute;
        }
        if (from != null) {
            clauses.add("(" + column + ">=? AND " + column + "<?)");
            Collections.addAll(arguments, from, last > Long.MAX_VALUE - 60000 ? Long.MAX_VALUE : last + 60000);
        }
        return clauses.toString();
    }
    private List<Map<String,Object>> visibleMixedPage(long symbol, List<Map<String,Object>> original) {
        HistoryOrdering.Policy ordering = historyOrdering.policyForPage(symbol);
        long owner = tenant(), first = time(original.get(0)), lastMinute = time(original.get(original.size()-1));
        long end = lastMinute > Long.MAX_VALUE - 60000 ? Long.MAX_VALUE : lastMinute + 60000;
        Set<Long> wanted = new HashSet<>(), modern = new HashSet<>();
        for (Map<String,Object> row : original) wanted.add(time(row));
        List<Object> check = new ArrayList<>(Arrays.asList(owner, symbol, first, end));
        String modernWindow = minuteWindows("s.generated_at", wanted, check); check.add(1);
        if (db.queryForList("SELECT s.generated_at FROM market_control_sample s JOIN market_control_task t ON t.tenant_id=s.tenant_id AND t.id=s.task_id JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id WHERE t.tenant_id=? AND t.symbol_id=? AND s.generated_at>=? AND s.generated_at<? AND " + modernWindow + " LIMIT ?",check.toArray()).isEmpty()) return original;
        Map<Long,MinuteEvents> events = new HashMap<>();
        List<Object> prefixArguments = new ArrayList<>(Arrays.asList(owner, symbol, first, end));
        String prefixWindow = minuteWindows("minute_at", wanted, prefixArguments); prefixArguments.add(HISTORY_PAGE_SIZE);
        List<Map<String,Object>> prefixes = db.queryForList("SELECT minute_at,body,last_event FROM market_legacy_minute_snapshot WHERE tenant_id=? AND symbol_id=? AND minute_at>=? AND minute_at<? AND " + prefixWindow + " ORDER BY minute_at LIMIT ?", prefixArguments.toArray());
        for (Map<String,Object> prefix : prefixes) {
            long at = ((Number)prefix.get("minute_at")).longValue();
            if (wanted.contains(at)) events.put(at, new MinuteEvents(at, decode((String)prefix.get("body")), ((Number)prefix.get("last_event")).longValue()));
        }
        long generated = first; String task = null;
        while (true) {
            List<Object> arguments = new ArrayList<>(Arrays.asList(owner, symbol, first, end));
            String sampleWindow = minuteWindows("s.generated_at", wanted, arguments);
            String after = "";
            if (task != null) {
                after = " AND (s.generated_at>? OR (s.generated_at=? AND t.id>?))";
                Collections.addAll(arguments, generated, generated, task);
            }
            arguments.add(HISTORY_PAGE_SIZE);
            List<Map<String,Object>> samples = db.queryForList("SELECT s.generated_at,s.price,t.id AS task_id,f.task_id AS modern_task,CASE WHEN f.task_id IS NULL OR f.state<>'SOURCE' OR (s.generated_at>=p.from_at AND s.generated_at<=p.to_at) THEN 1 ELSE 0 END AS is_visible FROM market_control_sample s JOIN market_control_task t ON t.tenant_id=s.tenant_id AND t.id=s.task_id LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id LEFT JOIN market_control_publication p ON p.tenant_id=t.tenant_id AND p.task_id=t.id WHERE t.tenant_id=? AND t.symbol_id=? AND s.generated_at>=? AND s.generated_at<? AND " + sampleWindow + after + " ORDER BY s.generated_at,t.id LIMIT ?", arguments.toArray());
            for (Map<String,Object> sample : samples) {
                long at = ((Number)sample.get("generated_at")).longValue()/60000*60000;
                if (!wanted.contains(at)) continue;
                if (sample.get("modern_task") != null) modern.add(at);
                if (((Number)sample.get("is_visible")).intValue() != 0) events.computeIfAbsent(at, key -> new MinuteEvents(key, new LinkedHashMap<>(), Long.MIN_VALUE)).add(sample);
            }
            if (samples.size() < HISTORY_PAGE_SIZE) break;
            Map<String,Object> last = samples.get(samples.size()-1);
            generated = ((Number)last.get("generated_at")).longValue(); task = (String)last.get("task_id");
        }
        if (modern.isEmpty()) return original;
        // Retain actual S1 manual display points; raw ticks cannot replace their offset prices.
        Set<Long> manualTimes = new HashSet<>();
        for (Map<String,Object> minute : original) for (Map<String,Object> point : manualPoints(minute)) {
            manualTimes.add(((Number)point.get("generated_at")).longValue());
            events.computeIfAbsent(time(minute), key -> new MinuteEvents(key, new LinkedHashMap<>(), Long.MIN_VALUE)).add(point);
        }
        long received = first, sequence = -1, sourceTime = Long.MIN_VALUE;
        long previousLegacyReceived = Long.MIN_VALUE;
        BigDecimal previousLegacyPrice = null;
        while (true) {
            List<Object> arguments = new ArrayList<>();
            String eventBranch = visibleSourceBranch(false, owner, symbol, first, end, received, sequence, sourceTime, wanted, arguments);
            String tickBranch = visibleSourceBranch(true, owner, symbol, first, end, received, sequence, sourceTime, wanted, arguments);
            arguments.add(HISTORY_PAGE_SIZE);
            List<Map<String,Object>> ticks = db.queryForList("SELECT e.received_at AS generated_at,e.price,e.event_sequence,e.source_time FROM ((" + eventBranch + ") UNION ALL (" + tickBranch + ")) e ORDER BY e.received_at,e.event_sequence,e.source_time LIMIT ?", arguments.toArray());
            for (Map<String,Object> tick : ticks) {
                MinuteEvents minute = events.get(((Number)tick.get("generated_at")).longValue()/60000*60000);
                long at=((Number)tick.get("generated_at")).longValue();
                historyOrdering.futureEvent(ordering, at, ((Number)tick.get("event_sequence")).longValue());
                if(minute==null || manualTimes.contains(at) || at<=minute.prefixEnd) continue;
                if(((Number)tick.get("event_sequence")).longValue()==0 && modern.contains(minute.minute)) {
                    BigDecimal price=number(tick.get("price"));
                    if(previousLegacyReceived==at && previousLegacyPrice.compareTo(price)!=0)
                        throw new com.gtcfesk.exchange.common.BusinessException("HISTORY_LEGACY_ORDER_PENDING: 旧事实顺序及已发布结果须独立审查; tenant="+owner+"; symbol="+symbol+"; minute="+minute.minute+"; receivedAt="+at);
                    previousLegacyReceived=at;previousLegacyPrice=price;
                }
                minute.add(tick);
            }
            if (ticks.size() < HISTORY_PAGE_SIZE) break;
            Map<String,Object> last = ticks.get(ticks.size()-1);
            received = ((Number)last.get("generated_at")).longValue(); sequence = ((Number)last.get("event_sequence")).longValue(); sourceTime = ((Number)last.get("source_time")).longValue();
        }
        List<Map<String,Object>> result = new ArrayList<>(original.size());
        for (Map<String,Object> minute : original) {
            long at = time(minute);
            if (!modern.contains(at)) { result.add(minute); continue; }
            MinuteEvents points = events.get(at);
            if (points == null) continue; // Preserve caller's original source candle when no control events are visible.
            points.bar.put("sourceCoverage", "observed_events_only"); points.bar.put("partial", true);
            result.add(points.bar);
        }
        return result;
    }
    /** Window, keyset and visibility predicates occur in both bounded UNION branches. */
    private static String visibleSourceBranch(boolean legacy, long owner, long symbol, long first, long end,
            long received, long sequence, long sourceTime, Collection<Long> wanted, List<Object> arguments) {
        String seq = legacy ? "0" : "e.event_sequence";
        String sql = "SELECT e.symbol_id,e.received_at,e.price," + seq + " AS event_sequence,e.source_time FROM "
            + (legacy ? "market_source_tick" : "market_source_event") + " e WHERE e.tenant_id=? AND e.symbol_id=? AND e.received_at>=? AND e.received_at<?"
            + " AND (e.received_at>? OR (e.received_at=? AND (" + seq + ">? OR (" + seq + "=? AND e.source_time>?))))";
        Collections.addAll(arguments, owner, symbol, first, end, received, received, sequence, sequence, sourceTime);
        sql += " AND " + minuteWindows("e.received_at", wanted, arguments);
        if (legacy) sql += " AND NOT EXISTS (SELECT 1 FROM market_source_event d WHERE d.tenant_id=e.tenant_id AND d.symbol_id=e.symbol_id AND d.source_time=e.source_time AND d.received_at=e.received_at AND d.price=e.price)";
        sql += " AND NOT EXISTS (SELECT 1 FROM market_control_task t LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id LEFT JOIN market_control_publication p ON p.tenant_id=t.tenant_id AND p.task_id=t.id WHERE t.tenant_id=? AND t.symbol_id=e.symbol_id AND e.received_at>=t.started_at AND e.received_at<=COALESCE(f.finished_at, CASE WHEN f.task_id IS NOT NULL THEN ? ELSE t.ended_at END) AND (f.task_id IS NULL OR f.state<>'SOURCE' OR (e.received_at>=p.from_at AND e.received_at<=p.to_at))) ORDER BY e.received_at,event_sequence,e.source_time LIMIT ?";
        Collections.addAll(arguments, owner, Long.MAX_VALUE, HISTORY_PAGE_SIZE); return sql;
    }
    /** At most one prefix and two ordering keys per minute; seconds never accumulate in a list. */
    private static final class MinuteEvents {
        final long minute, prefixEnd; final Map<String,Object> bar;
        final boolean frozenOpen; Map<String,Object> first, last;
        MinuteEvents(long minute, Map<String,Object> prefix, long prefixEnd) {
            this.minute = minute; this.bar = prefix; this.prefixEnd = prefixEnd; frozenOpen = !prefix.isEmpty();
        }
        void add(Map<String,Object> point) {
            if (((Number)point.get("generated_at")).longValue() <= prefixEnd) return;
            Object close = bar.get("close_price");
            addPrice(bar, minute, number(point.get("price")));
            if (first == null || compare(point, first) < 0) {
                first = point;
                if (!frozenOpen) bar.put("open_price", number(point.get("price")));
            }
            if (last == null || compare(point, last) > 0) last = point;
            else bar.put("close_price", close);
        }
        private static int compare(Map<String,Object> a, Map<String,Object> b) {
            int value = Long.compare(((Number)a.get("generated_at")).longValue(), ((Number)b.get("generated_at")).longValue());
            if (value != 0) return value;
            long as = a.get("event_sequence") instanceof Number ? ((Number)a.get("event_sequence")).longValue() : Long.MAX_VALUE;
            long bs = b.get("event_sequence") instanceof Number ? ((Number)b.get("event_sequence")).longValue() : Long.MAX_VALUE;
            value = Long.compare(as, bs);
            if (value != 0) return value;
            // Ambiguous legacy prices are rejected above, never approved by an incidental physical tie order.
            if (as == 0) return Long.compare(((Number)b.get("source_time")).longValue(), ((Number)a.get("source_time")).longValue());
            return String.valueOf(a.get("task_id")).compareTo(String.valueOf(b.get("task_id")));
        }
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
        // Latest-only: an exact mirror cannot win against its positive event_sequence. Searching for an
        // unmatched legacy tick scans the entire mirrored ledger. Full-history reads still deduplicate.
        // Tick PK (tenant_id,symbol_id,source_time) is unique, so its other ORDER BY keys cannot break a tie.
        String ticks = "SELECT t.symbol_id,t.source_time,t.received_at,t.price,0 AS event_sequence FROM market_source_tick t WHERE t.tenant_id=" + tenant() + " AND " + predicate
            + (latest ? " ORDER BY t.source_time DESC LIMIT 1" : " AND NOT EXISTS (SELECT 1 FROM market_source_event e WHERE e.tenant_id=t.tenant_id AND e.symbol_id=t.symbol_id AND e.source_time=t.source_time AND e.received_at=t.received_at AND e.price=t.price)");
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
    // Repair facts are chart-only. Filter before LIMIT so they cannot displace a real control start basis.
    Map<String, Object> lastClose(long symbol, long now) {
        List<Map<String, Object>> rows = db.query("SELECT body,period,received_at FROM market_source_candle WHERE tenant_id=" + tenant() + " AND symbol_id=? AND candle_at<? AND body NOT LIKE ? ORDER BY candle_at DESC LIMIT 100",
            (rs, n) -> { Map<String, Object> row = decode(rs.getString(1)); row.put("period", rs.getString(2)); row.put("receivedAt", rs.getLong(3)); return row; }, symbol, now, "%\"historyOnly\":true%");
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
