package com.gtcfesk.exchange.market;

import static com.gtcfesk.exchange.market.ControlHistoryStore.tenant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.entity.TradingSymbol;
import lombok.Getter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.*;

/** Target lifecycle is independent of provider availability and the mutable TradingSymbol settings. */
@Service
public class PersistentPriceControl {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher events;
    private final ControlHistoryStore store;
    private final ControlHoldService holds;
    private final ControlRecoveryFlow flows;
    final ThreadLocal<Long> commandSeed=new ThreadLocal<>();
    @org.springframework.beans.factory.annotation.Value("${market.quote.max-age-ms:60000}") private long maxAgeMs=60000;
    public PersistentPriceControl(ControlHistoryStore store) { this.store = store; this.holds = new ControlHoldService(store); this.flows = new ControlRecoveryFlow(store, holds); }
    <T> T locked(long symbol, java.util.function.Supplier<T> operation) {
        return store.withPlans(Collections.singletonList(symbol), () -> store.locked(symbol, operation));
    }
    @Getter public static class Task {
        String id, symbol, kind, status, startSource;
        long tenantId, symbolId, sourceTime, startedAt, plannedEnd, sampledUntil;
        Long endedAt;
        Long historyReplacedAt;
        boolean holding;
        Long stopAt;
        BigDecimal startPrice, targetPrice;
        int durationSeconds, intensity, pricePrecision, algorithmVersion;
        boolean oscillation;
        @JsonIgnore transient ControlHistoryStore store;
        @JsonIgnore transient TargetControlPlan plan;
        boolean running() { return "RUNNING".equals(status); }
        TradingSymbol path() {
            if (algorithmVersion != 1 && algorithmVersion != 2) throw new IllegalStateException("Unsupported control algorithm " + algorithmVersion);
            TradingSymbol p = new TradingSymbol(); p.setSymbol(symbol); p.setPricePrecision(pricePrecision);
            p.setControlStartPrice(startPrice); p.setControlTargetPrice(targetPrice); p.setControlStartedAt(startedAt);
            p.setControlDurationSeconds(durationSeconds); p.setControlIntensity(intensity); p.setControlRandomOscillation(oscillation);
            return p;
        }
        BigDecimal price(long time) {
            TenantContext.require(tenantId);
            if (algorithmVersion == BalancedControlPlan.VERSION || algorithmVersion == StabilizedControlPlan.VERSION) {
                if (plan == null) plan = store.plan(id);
                return plan.price(startedAt, time);
            }
            return PriceControlPath.price(path(), time, algorithmVersion);
        }
    }
    private static final String TASK_SELECT = "SELECT t.*,h.activated_at,h.released_at,p.published_at FROM market_control_task t "
        + "LEFT JOIN market_control_hold h ON h.tenant_id=t.tenant_id AND h.task_id=t.id LEFT JOIN market_control_publication p ON p.tenant_id=t.tenant_id AND p.task_id=t.id ";
    private static final RowMapper<Task> TASK = (r, n) -> {
        Task t = new Task(); t.tenantId = r.getLong("tenant_id"); t.id = r.getString("id"); t.symbolId = r.getLong("symbol_id"); t.symbol = r.getString("symbol");
        t.kind = r.getString("kind"); t.status = r.getString("status"); t.startSource = r.getString("start_source");
        t.sourceTime = r.getLong("source_time"); t.startedAt = r.getLong("started_at"); t.plannedEnd = r.getLong("planned_end");
        t.stopAt = (Long)r.getObject("stop_at");
        t.sampledUntil = r.getLong("sampled_until"); t.endedAt = (Long) r.getObject("ended_at");
        t.startPrice = r.getBigDecimal("start_price"); t.targetPrice = r.getBigDecimal("target_price");
        t.durationSeconds = r.getInt("duration_seconds"); t.intensity = r.getInt("intensity");
        t.pricePrecision = r.getInt("price_precision"); t.algorithmVersion = r.getInt("algorithm_version");
        t.oscillation = r.getBoolean("oscillation");
        t.holding = r.getObject("activated_at") != null && r.getObject("released_at") == null;
        t.historyReplacedAt = (Long) r.getObject("published_at"); return t;
    };
    public Task startRealtimeRestore(TradingSymbol config, Map<String,Object> raw, BigDecimal displayed,
            int duration, int intensity, boolean oscillation, String requestKey) {
        TenantContext.require(config.getTenantId());
        return store.locked(config.getId(), () -> {
            if(requestKey!=null) {
                List<Task> existing=store.db.query(TASK_SELECT+"WHERE t.tenant_id=? AND t.symbol_id=? AND t.request_key=?",TASK,tenant(),config.getId(),requestKey);
                if(!existing.isEmpty()) {
                    Task t=existing.get(0);t.store=store;
                    if(!"RESTORE".equals(t.kind) || t.durationSeconds!=duration || t.intensity!=intensity || t.oscillation!=oscillation)
                        throw new BusinessException("任务请求标识已用于不同参数");
                    return t;
                }
            }
            if(!Boolean.TRUE.equals(raw.get("available")) || !QuoteState.valid(raw))throw new BusinessException("原始行情不可用，无法开始渐进恢复");
            long now=System.currentTimeMillis();Task previous=latest(config.getId());
            Map<String,Object> previousFlow=previous==null?Collections.emptyMap():flows.get(previous.id);
            Map<String,Object> committed=store.runtime.read(config.getId(),false,now);
            BigDecimal start=committed.get("price") instanceof Number && QuoteState.valid(committed)?ControlHistoryStore.number(committed.get("price")):displayed;
            if(start==null || start.signum()<=0)throw new BusinessException("没有已提交的有效控盘价格");
            // Supersede only committed state. Recovery never needs the old endpoint or hold activation.
            endCommitted(previous,now);
            store.runtime.invalidate(config.getId());
            if(previous!=null)now=Math.max(now,Math.max(previous.startedAt,previous.sampledUntil)+1);
            store.captureLegacyMinute(config.getId(),now);store.freeze(config.getId(),now);
            String id=UUID.randomUUID().toString();
            store.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until,ended_at,request_key) VALUES(?,?,?,?,2,'RESTORE','COMPLETED',?,?,?,?,?,?,'CONTROL_DISPLAY',?,?,?,?,?,?)",
                tenant(),id,config.getId(),config.getSymbol(),start,raw.get("price"),duration,intensity,oscillation,PriceControlPath.precision(config),QuoteState.time(raw.get("sourceTimestamp")),now,now+duration*1000L,now,now,requestKey);
            Task task=latest(config.getId());RecoveryOptions options=new RecoveryOptions();options.setAutoRestore(true);
            options.setRestoreDurationSeconds(duration);options.setRestoreIntensity(intensity);options.setRestoreRandomOscillation(oscillation);
            if(!previousFlow.isEmpty())options.setAutoReplaceHistory(Boolean.TRUE.equals(store.decode((String)previousFlow.get("options_json")).get("autoReplaceHistory")));
            flows.create(task,options);
            store.generatedPoints(id,config.getId(),Collections.singletonList(new ControlHistoryStore.PricePoint(now,start)));
            store.db.update("UPDATE market_control_flow SET state='WAITING_SOURCE' WHERE tenant_id=? AND task_id=?",tenant(),id);
            return task;
        });
    }
    /** Authorized emergency SOURCE: no catch-up, plan decoding, hold activation or ledger scan. */
    public void emergencySource(long symbol,long now) {
        store.locked(symbol,()->{
            store.runtime.invalidate(symbol);
            store.db.update("UPDATE market_control_command SET state='CANCELLED',prepared_json=NULL,error_code='CONTROL_CANCELLED',message=? WHERE tenant_id=? AND symbol_id=? AND state IN ('ACCEPTED','PREPARING','READY')",ControlHistoryStore.encodeCommandMessage("应急回源已取消准备"),tenant(),symbol);
            Task task=latest(symbol);endCommitted(task,now);
            org.slf4j.LoggerFactory.getLogger(getClass()).info("control_source tenant={} symbol={} task={} sampledUntil={} plannedEnd={} missingSamplesDiscarded=true",tenant(),symbol,task==null?null:task.id,task==null?null:task.sampledUntil,task==null?null:task.plannedEnd);
            return null;
        });
    }
    private void endCommitted(Task task,long now) {
        if(task==null)return;
        flows.source(task,now);holds.release(task.id,now);
        // STOPPED ends at the last committed sample, not an invented wall-clock endpoint.
        if(task.running())store.db.update("UPDATE market_control_task SET status='STOPPED',ended_at=?,stop_at=? WHERE tenant_id=? AND id=?",Math.max(task.startedAt,task.sampledUntil),now,tenant(),task.id);
        else store.db.update("UPDATE market_control_task SET stop_at=? WHERE tenant_id=? AND id=?",now,tenant(),task.id);
    }
    public boolean hasFlow(long symbol) {
        Task t = latest(symbol);
        return t != null && !flows.get(t.id).isEmpty();
    }
    public Task latest(long symbol) {
        List<Task> rows = store.db.query(TASK_SELECT + "WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? ORDER BY t.started_at DESC,t.id DESC LIMIT 1", TASK, symbol);
        if (rows.isEmpty()) return null;
        rows.get(0).store = store; return rows.get(0);
    }
    public List<Task> history(long symbol, Long before) {
        return store.db.query(TASK_SELECT + "WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? AND t.started_at<? ORDER BY t.started_at DESC LIMIT 100", TASK,
            symbol, before == null ? Long.MAX_VALUE : before);
    }
    public List<Long> runningSymbols() {
        return store.db.queryForList("SELECT DISTINCT t.symbol_id FROM market_control_task t LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id WHERE t.tenant_id=" + tenant() + " AND (t.status='RUNNING' OR f.state IN ('WAITING_SOURCE','RECOVERING'))", Long.class);
    }
    public Task replaceHistory(long symbol, String taskId) {
        return locked(symbol, () -> {
            advance(latest(symbol), System.currentTimeMillis());
            List<Task> tasks = store.db.query(TASK_SELECT + "WHERE t.tenant_id=" + tenant() + " AND t.id=? AND t.symbol_id=?", TASK, taskId, symbol);
            if (tasks.isEmpty()) throw new BusinessException("控盘任务不存在");
            Task task = tasks.get(0);
            if (task.running() || task.endedAt == null) throw new BusinessException("目标轨迹结束后才能替代历史行情");
            Map<String,Object> flow = flows.get(task.id);
            if (!flow.isEmpty()) {
                if (flows.publish(task, Math.max(task.sampledUntil, ((Number)flow.get("last_at")).longValue())))
                    store.runtime.historyPublished(symbol);
                return store.db.queryForObject(TASK_SELECT + "WHERE t.tenant_id=" + tenant() + " AND t.id=?", TASK, task.id);
            }
            if (!store.historyOrdering.publicationNeeded(symbol, task.id, task.startedAt, task.endedAt)) return task;
            // Publish the original interval. The existing authoritative mixed minutes already preserve
            // its source snapshot and actual later events; copying whole candles would erase those events.
            store.db.update("INSERT INTO market_control_publication(tenant_id,task_id,published_at,from_at,to_at) VALUES(" + tenant() + ",?,?,?,?) ON DUPLICATE KEY UPDATE task_id=VALUES(task_id)",
                task.id, System.currentTimeMillis(), task.startedAt, task.endedAt);
            store.runtime.historyPublished(symbol);
            return store.db.queryForObject(TASK_SELECT + "WHERE t.tenant_id=" + tenant() + " AND t.id=?", TASK, task.id);
        });
    }
    public void importLegacy(TradingSymbol config) {
        TenantContext.require(config.getTenantId());
        if (!PriceControlPath.running(config)) return;
        locked(config.getId(), () -> {
            String key = "legacy-" + config.getControlStartedAt();
            if (store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=" + tenant() + " AND symbol_id=? AND request_key=?", Integer.class, config.getId(), key) == 0) {
                store.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until,request_key) VALUES(" + tenant() + ",?,?,?,1,'TARGET','RUNNING',?,?,?,?,?,?,'LEGACY_PARAMETERS',?,?,?,?,?)",
                    UUID.randomUUID().toString(), config.getId(), config.getSymbol(), config.getControlStartPrice(), config.getControlTargetPrice(),
                    config.getControlDurationSeconds(), config.getControlIntensity(), Boolean.TRUE.equals(config.getControlRandomOscillation()), PriceControlPath.precision(config),
                    config.getControlStartedAt(), config.getControlStartedAt(), PriceControlPath.endsAt(config), config.getControlStartedAt() - 1000, key);
            }
            return null;
        });
    }
    public void advance(long symbol, long now) { locked(symbol, () -> { advance(latest(symbol), now); return null; }); }
    private void advance(Task task, long now) {
        if (task == null || !task.running()) return;
        long until = Math.min(now, task.stopAt == null ? task.plannedEnd : Math.min(task.plannedEnd, task.stopAt));
        int allowance=store.runtime.sampleAllowance(task.symbolId);
        if(allowance==0) return;
        until = Math.min(until, Math.max(task.startedAt - 1000, task.sampledUntil) + allowance*1000L);
        long before=task.sampledUntil;
        // Each bounded sub-batch is persisted before checking elapsed time again. The physical
        // transaction still shares the existing 512-point allowance across symbols/nested calls.
        while(task.sampledUntil<until && store.runtime.hasBudget(2000)) {
            List<ControlHistoryStore.PricePoint> points=new ArrayList<>();
            long first=task.sampledUntil<task.startedAt?task.startedAt:task.sampledUntil+1000;
            for(long time=first;time<=until && points.size()<64 && store.runtime.hasBudget(2000);time+=1000)
                points.add(new ControlHistoryStore.PricePoint(time,task.price(time)));
            if(points.isEmpty())break;
            store.runtime.phase(task.symbolId,"sampling",()->{store.generatedPoints(task.id,task.symbolId,points);return null;});
            task.sampledUntil=points.get(points.size()-1).generatedAt;
        }
        if (task.sampledUntil >= task.plannedEnd && (task.stopAt == null || task.stopAt >= task.plannedEnd) && store.runtime.hasBudget(1500)) {
            store.runtime.phase(task.symbolId,"hold_activation",()->{holds.activate(task);return null;});
            task.status="COMPLETED";task.endedAt=task.plannedEnd;
        }
        org.slf4j.LoggerFactory.getLogger(getClass()).info("control_progress tenant={} symbol={} task={} before={} after={} expected={} state={}",tenant(),task.symbolId,task.id,before,task.sampledUntil,until,task.status);
        store.db.update("UPDATE market_control_task SET sampled_until=?,status=?,ended_at=? WHERE tenant_id=" + tenant() + " AND id=?",
            task.sampledUntil, task.status, task.endedAt, task.id);
    }
    public Map<String, Object> startBasis(TradingSymbol config, Map<String, Object> raw, BigDecimal displayed, long now) {
        TenantContext.require(config.getTenantId());
        Map<String, Object> basis = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(raw.get("available")) && displayed != null && displayed.signum() > 0) {
            basis.put("price", displayed); basis.put("source", "LIVE_DISPLAY"); basis.put("timestamp", raw.get("sourceTimestamp"));
        } else {
            Map<String, Object> candle = store.lastClose(config.getId(), now);
            // A completed mixed minute is also historical price, and wins over its original minute.
            List<Map<String, Object>> mixed = store.db.query("SELECT body FROM market_mixed_minute WHERE tenant_id=" + tenant() + " AND symbol_id=? AND minute_at+60000<=? ORDER BY minute_at DESC LIMIT 1",
                (rs, n) -> store.decode(rs.getString(1)), config.getId(), now);
            if (!mixed.isEmpty() && (candle.isEmpty() || ControlHistoryStore.time(mixed.get(0)) + 60000
                    >= RandomMarketPath.periodEnd(String.valueOf(candle.get("period")), ControlHistoryStore.time(candle)))) candle = mixed.get(0);
            if (!candle.isEmpty()) {
                basis.put("price", candle.get("close_price")); basis.put("source", "COMPLETED_CANDLE");
                basis.put("timestamp", RandomMarketPath.periodEnd(String.valueOf(candle.getOrDefault("period", "1m")), ControlHistoryStore.time(candle)));
            } else {
                Map<String, Object> saved = store.lastQuote(config.getId());
                if (QuoteState.valid(raw) && QuoteState.time(raw.get("timestamp")) >= QuoteState.time(saved.get("timestamp"))) saved = raw;
                if (saved.get("price") != null) {
                    basis.put("price", saved.get("price")); basis.put("source", "LAST_VALID_QUOTE"); basis.put("timestamp", saved.get("timestamp"));
                }
            }
        }
        return basis;
    }
    public Task existingTarget(long symbol, String requestKey, int duration, BigDecimal target, int intensity,
            boolean oscillation, RecoveryOptions options) {
        return existingTarget(symbol, requestKey, duration, target, intensity, oscillation, options, null);
    }
    public Task existingTarget(long symbol, String requestKey, int duration, BigDecimal target, int intensity,
            boolean oscillation, RecoveryOptions options, TargetControlOptions targetOptions) {
        if (requestKey == null) return null;
        List<Task> rows = store.db.query(TASK_SELECT + "WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? AND t.request_key=?", TASK, symbol, requestKey);
        if (rows.isEmpty()) return null;
        Task task = rows.get(0); task.store = store;
        if (!"TARGET".equals(task.kind) || task.durationSeconds != duration || task.intensity != intensity
                || task.oscillation != oscillation || task.targetPrice.compareTo(target) != 0)
            throw new BusinessException("任务请求标识已用于不同参数");
        if (targetOptions != null && (task.algorithmVersion != StabilizedControlPlan.VERSION
                || !TargetControlSettings.identity(store.plan(task.id).snapshot()).equals(TargetControlSettings.identity(targetOptions))))
            throw new BusinessException("任务请求标识已用于不同幅度公式或偏差带参数");
        Map<String, Object> flow = flows.get(task.id);
        RecoveryOptions effective = options == null ? new RecoveryOptions() : options;
        if (options == null) effective.setAutoReplaceHistory(false);
        if ((task.algorithmVersion >= BalancedControlPlan.VERSION || options != null)
                && (flow.isEmpty() || !store.decode((String) flow.get("options_json")).equals(effective.snapshot())))
            throw new BusinessException("任务请求标识已用于不同恢复参数");
        return task;
    }
    public static final class Prepared implements AutoCloseable {
        ControlPlanBudget.Lease allocation;
        @Override public void close(){if(allocation!=null)allocation.close();}
        final TargetControlPlan plan;
        final ControlHistoryStore.EncodedPlan encoded;
        final long tenantId, symbolId;
        final Long revision;
        final long seed;
        final String previousTaskId;
        final BigDecimal start;
        Prepared(TargetControlPlan plan, long seed, String previousTaskId, BigDecimal start, TradingSymbol config, ControlHistoryStore.EncodedPlan encoded) {
            this.encoded = encoded; this.tenantId = config.getTenantId(); this.symbolId = config.getId(); this.revision = config.getRowVersion();
            this.plan = plan; this.seed = seed; this.previousTaskId = previousTaskId; this.start = start;
        }
        public Map<String, Object> preview() {
            Map<String, Object> result = new LinkedHashMap<>(plan.preview());
            result.put("summary", plan.summary()); result.put("checksum", plan.checksum()); result.put("algorithmVersion", plan.version());
            return result;
        }
    }
    /** Generate outside the symbol lock and the write transaction. */
    public BigDecimal previewStart(TradingSymbol config, Map<String, Object> raw, BigDecimal displayed) {
        TenantContext.require(config.getTenantId());
        Task previous = latest(config.getId());
        Map<String, Object> basis = startBasis(config, raw, displayed, System.currentTimeMillis());
        if (previous != null && previous.holding) {
            List<BigDecimal> held = store.db.queryForList("SELECT last_price FROM market_control_hold WHERE tenant_id=" + tenant() + " AND task_id=? AND released_at IS NULL", BigDecimal.class, previous.id);
            if (!held.isEmpty()) basis.put("price", held.get(0));
        }
        if (basis.get("price") == null) throw new BalancedControlPlan.Failure("INVALID_PARAMETERS", "没有有效起点价格");
        return ControlHistoryStore.number(basis.get("price")).setScale(PriceControlPath.precision(config), RoundingMode.HALF_UP);
    }
    public Prepared prepare(TradingSymbol config, Map<String, Object> raw, BigDecimal displayed,
            int duration, BigDecimal target, int intensity, boolean oscillation) {
        TenantContext.require(config.getTenantId());
        Task previous = latest(config.getId());
        int precision = PriceControlPath.precision(config);
        BigDecimal start = previewStart(config, raw, displayed);
        BalancedControlPlan.Parameters p = new BalancedControlPlan.Parameters(start, target, duration, precision, intensity,
                BalancedControlPlan.DEFAULT_RATIO);
        long seed = commandSeed.get()!=null?commandSeed.get():oscillation ? new SecureRandom().nextLong() : Objects.hash(p.snapshot());
        TargetControlPlan plan = BalancedControlPlan.generate(p, seed);
        return new Prepared(plan, seed, previous == null ? null : previous.id, start, config, store.encodePlan(plan));
    }
    public Prepared prepare(TradingSymbol config, Map<String, Object> raw, BigDecimal displayed,
            int duration, BigDecimal target, int intensity, boolean oscillation, TargetControlOptions options) {
        TenantContext.require(config.getTenantId());
        Task previous = latest(config.getId());
        int precision = PriceControlPath.precision(config);
        BigDecimal start = previewStart(config, raw, displayed);
        TargetControlSettings settings = new TargetControlSettings(start, target, duration, precision, intensity, options);
        StabilizedControlPlan.Parameters p = new StabilizedControlPlan.Parameters(start, target, duration, precision, intensity,
                StabilizedControlPlan.DEFAULT_RATIO, settings);
        long seed = commandSeed.get()!=null?commandSeed.get():oscillation ? new SecureRandom().nextLong() : Objects.hash(p.snapshot());
        TargetControlPlan plan = StabilizedControlPlan.generate(p, seed);
        return new Prepared(plan, seed, previous == null ? null : previous.id, start, config, store.encodePlan(plan));
    }
    public Task startPrepared(TradingSymbol config, Map<String, Object> raw, BigDecimal displayed, int duration,
            BigDecimal target, int intensity, boolean oscillation, String requestKey, RecoveryOptions options, Prepared prepared) {
        TenantContext.require(config.getTenantId());
        return locked(config.getId(), () -> {
            if (options != null && requestKey != null && store.db.queryForObject("SELECT COUNT(*) FROM market_control_task t LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? AND t.request_key=? AND f.task_id IS NULL", Integer.class, config.getId(), requestKey) > 0)
                throw new BusinessException("旧任务请求标识不可追加自动恢复配置");
            Task task = startLocked(config, raw, displayed, duration, target, intensity, oscillation, false, requestKey, prepared);
            RecoveryOptions effective = options == null ? new RecoveryOptions() : options;
            if (options == null) effective.setAutoReplaceHistory(false);
            Map<String,Object> flow = flows.get(task.id);
            if (flow.isEmpty()) flows.create(task, effective);
            else if (!store.decode((String)flow.get("options_json")).equals(effective.snapshot()))
                throw new BusinessException("任务请求标识已用于不同恢复参数");
            return task;
        });
    }
    public Task start(TradingSymbol config, Map<String, Object> raw, BigDecimal displayed, int duration, BigDecimal target,
            int intensity, boolean oscillation, boolean restore, String requestKey, RecoveryOptions options) {
        TenantContext.require(config.getTenantId());
        return locked(config.getId(), () -> {
            if (requestKey != null && store.db.queryForObject("SELECT COUNT(*) FROM market_control_task t LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? AND t.request_key=? AND f.task_id IS NULL", Integer.class, config.getId(), requestKey) > 0)
                throw new BusinessException("旧任务请求标识不可追加自动恢复配置");
            Task task = start(config, raw, displayed, duration, target, intensity, oscillation, restore, requestKey);
            Map<String,Object> flow = flows.get(task.id);
            if (flow.isEmpty()) flows.create(task, options);
            else if (!store.decode((String)flow.get("options_json")).equals(options.snapshot()))
                throw new BusinessException("任务请求标识已用于不同恢复参数");
            return task;
        });
    }
    public Task start(TradingSymbol config, Map<String, Object> raw, BigDecimal displayed, int duration, BigDecimal target,
            int intensity, boolean oscillation, boolean restore, String requestKey) {
        TenantContext.require(config.getTenantId());
        return locked(config.getId(), () -> startLocked(config, raw, displayed, duration, target, intensity, oscillation, restore, requestKey, null));
    }
    private Task startLocked(TradingSymbol config, Map<String, Object> raw, BigDecimal displayed, int duration, BigDecimal target,
            int intensity, boolean oscillation, boolean restore, String requestKey, Prepared prepared) {
            if (requestKey != null) {
                if (requestKey.length() > 64 || requestKey.trim().isEmpty()) throw new BusinessException("任务请求标识无效");
                List<Task> previous = store.db.query(TASK_SELECT + "WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? AND t.request_key=?", TASK, config.getId(), requestKey);
                if (!previous.isEmpty()) {
                    Task task = previous.get(0);
                    if (task.durationSeconds != duration || task.intensity != intensity || task.oscillation != oscillation
                            || !task.kind.equals(restore ? "RESTORE" : "TARGET") || !restore && task.targetPrice.compareTo(target) != 0)
                        throw new BusinessException("任务请求标识已用于不同参数");
                    if (prepared != null && (task.algorithmVersion != prepared.plan.version()
                            || task.algorithmVersion == StabilizedControlPlan.VERSION && !TargetControlSettings.identity(store.plan(task.id).snapshot())
                                .equals(TargetControlSettings.identity(prepared.plan.snapshot()))))
                        throw new BusinessException("任务请求标识已用于不同幅度公式或偏差带参数");
                    task.store = store;
                    return task;
                }
            }
            if (prepared != null && (prepared.tenantId != tenant() || prepared.symbolId != config.getId()
                    || !Objects.equals(prepared.revision, config.getRowVersion())))
                throw new BalancedControlPlan.Failure("START_BASIS_CHANGED", "预计算后租户、品种或配置版本已变化");
            if (prepared != null && (duration != ((Number) prepared.plan.snapshot().get("duration")).intValue()
                    || intensity != ((Number) prepared.plan.snapshot().get("intensity")).intValue()
                    || target.compareTo(new BigDecimal((String) prepared.plan.snapshot().get("target")).movePointLeft(prepared.plan.precision())) != 0))
                throw new BusinessException("启动参数与预计算轨迹不一致");
            long now = System.currentTimeMillis();
            Task old = latest(config.getId());
            if (prepared != null && (!Objects.equals(prepared.previousTaskId, old == null ? null : old.id)
                    || PriceControlPath.precision(config) != prepared.plan.precision()))
                throw new BalancedControlPlan.Failure("START_BASIS_CHANGED", "预计算后起点或任务状态已变化，请刷新预览");
            advance(old, now);
            if (old != null) now = Math.max(now, Math.max(old.startedAt, old.sampledUntil) + 1);
            if (old != null && !restore && (old.running() || Arrays.asList("RECOVERING", "WAITING_SOURCE").contains(flows.get(old.id).get("state")))) throw new BusinessException("自动控盘正在运行，请先停止任务");
            BigDecimal startingDisplay = displayed;
            Map<String, Object> basis = startBasis(config, raw, startingDisplay, now);
            Map<String,Object> hold = old == null ? Collections.emptyMap() : holds.observe(old, raw, now);
            if (!hold.isEmpty()) {
                basis.put("price", hold.get("last_price")); basis.put("source", "CONTROL_DISPLAY");
                basis.put("timestamp", hold.get("generated_at"));
                startingDisplay = ControlHistoryStore.number(hold.get("last_price"));
            }
            if (restore) {
                if (!Boolean.TRUE.equals(raw.get("available"))) throw new BusinessException("原始行情不可用，无法确定恢复目标");
                Map<String,Object> oldFlow = old == null ? Collections.emptyMap() : flows.get(old.id);
                basis.put("price", !oldFlow.isEmpty() && !"TARGET".equals(oldFlow.get("state")) && !"SOURCE".equals(oldFlow.get("state"))
                    ? oldFlow.get("last_price") : old != null && old.running() ? old.price(old.sampledUntil) : startingDisplay);
                basis.put("source", "CONTROL_DISPLAY");
                basis.put("timestamp", old != null && old.running() ? old.sampledUntil
                    : hold.isEmpty() ? raw.get("sourceTimestamp") : hold.get("generated_at"));
                stopLocked(old, now);
            }
            if (basis.get("price") == null || ControlHistoryStore.number(basis.get("price")).signum() <= 0)
                throw new BusinessException("没有有效历史价格，无法启动目标控盘");
            if (prepared != null && (!Objects.equals(prepared.previousTaskId, old == null ? null : old.id)
                    || ControlHistoryStore.number(basis.get("price")).setScale(prepared.plan.precision(), RoundingMode.HALF_UP).compareTo(prepared.start) != 0))
                throw new BalancedControlPlan.Failure("START_BASIS_CHANGED", "预计算后起点或任务状态已变化，请刷新预览");
            String id = UUID.randomUUID().toString();
            if (old != null) { flows.cancel(old, now, false); holds.release(old.id, now); }
            store.captureLegacyMinute(config.getId(), now);
            store.freeze(config.getId(), now);
            int algorithm = prepared == null ? 2 : prepared.plan.version();
            store.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until,request_key) VALUES(" + tenant() + ",?,?,?,? ,?,'RUNNING',?,?,?,?,?,?,?,?,?,?,?,?)",
                id, config.getId(), config.getSymbol(), algorithm, restore ? "RESTORE" : "TARGET", prepared == null ? basis.get("price") : prepared.start, target, duration, intensity, oscillation,
                PriceControlPath.precision(config), basis.get("source"), QuoteState.time(basis.get("timestamp")), now, now + duration * 1000L, now - 1000, requestKey);
            if (prepared != null) {
                store.savePlan(id, prepared.seed, prepared.encoded);
                store.rememberPlan(id, prepared.plan);
            }
            Task task = latest(config.getId());
            if (prepared != null) task.plan = prepared.plan;
            if (!restore) holds.prepare(task, raw);
            advance(task, now); return task;
    }
    public void stop(long symbol, long now) { locked(symbol, () -> { stopLocked(latest(symbol), now); return null; }); }
    /** Stop movement without restoring the source price; only an explicit restore releases the offset. */
    public void stopAndHold(long symbol, long now) {
        locked(symbol, () -> {
            Task task = latest(symbol);
            if(task!=null && task.running()) {
                if(task.stopAt==null) {
                    task.stopAt=now;store.db.update("UPDATE market_control_task SET stop_at=? WHERE tenant_id="+tenant()+" AND id=?",now,task.id);
                    store.runtime.invalidate(symbol);
                }
                advance(task,task.stopAt);
                if(task.sampledUntil+1000<=Math.min(task.stopAt,task.plannedEnd)) return null;
            }
            final long cutoff=task!=null && task.stopAt!=null?task.stopAt:now;
            Map<String,Object> flow = task == null ? Collections.emptyMap() : flows.get(task.id);
            if (!flow.isEmpty() && Arrays.asList("RECOVERING","WAITING_SOURCE").contains(flow.get("state"))) {
                store.db.update("DELETE FROM market_control_hold WHERE tenant_id=" + tenant() + " AND task_id=?", task.id);
                holds.prepare(task, store.lastQuote(symbol));
                holds.activate(task, cutoff, ControlHistoryStore.number(flow.get("last_price")));
            }
            flows.cancel(task, cutoff, true);
            if (task != null && task.running()) {
                if (holds.active(task.id).isEmpty()) {
                    // Restore tasks have no pending hold; their interrupted price must also be retained.
                    if (store.db.queryForObject("SELECT COUNT(*) FROM market_control_hold WHERE tenant_id=" + tenant() + " AND task_id=?", Integer.class, task.id) == 0)
                        holds.prepare(task, store.lastQuote(symbol));
                    holds.activate(task, cutoff, task.price(task.sampledUntil));
                }
                store.db.update("UPDATE market_control_task SET status='STOPPED',ended_at=? WHERE tenant_id=" + tenant() + " AND id=?", cutoff, task.id);
            }
            return null;
        });
    }
    private void stopLocked(Task task, long now) {
        if(task!=null) store.runtime.invalidate(task.symbolId);
        advance(task, now);
        if (task != null && task.running() && task.sampledUntil + 1000 <= Math.min(now, task.plannedEnd))
            throw new BalancedControlPlan.Failure("ENGINE_LAG", "市场引擎尚未追齐已发生的控盘历史，请稍后重试");
        if (task != null) { flows.cancel(task, now, false); holds.release(task.id, now); }
        if (task != null && task.running()) {
            store.db.update("UPDATE market_control_task SET status='STOPPED',ended_at=? WHERE tenant_id=" + tenant() + " AND id=?", now, task.id);
        }
    }
    private static boolean manual(TradingSymbol config, Task task) {
        return Boolean.TRUE.equals(config.getControlEnabled()) && !PriceControlPath.running(config) && (task == null || !task.running());
    }
    void recordManualPrice(TradingSymbol config, Map<String,Object> raw, long now) {
        TenantContext.require(config.getTenantId());
        if (Boolean.TRUE.equals(raw.get("available")) && raw.get("price") instanceof Number) {
            store.freeze(config.getId(), now);
            store.manualPoint(config.getId(), now, ForexQuoteMarketService.controlledPrice(config, raw, now));
        }
    }
    public void sourceQuote(TradingSymbol config, Map<String, Object> raw, long receivedAt) {
        TenantContext.require(config.getTenantId());
        locked(config.getId(), () -> {
            Map<String,Object> committed = store.lastQuote(config.getId());
            if (QuoteState.time(raw.get("timestamp")) < QuoteState.time(committed.get("timestamp"))) return null;
            if (!store.quote(config.getId(), raw, receivedAt)) return null;
            Map<String,Object> observedRaw=new LinkedHashMap<>(raw);observedRaw.put("sourceReceivedAt",receivedAt);
            Task task = latest(config.getId()); advance(task, receivedAt);
            Map<String,Object> flow = task == null ? Collections.emptyMap() : flows.get(task.id);
            if (!flow.isEmpty() && !"SOURCE".equals(flow.get("state"))) flows.observe(task, observedRaw, receivedAt);
            else if (task != null && !holds.active(task.id).isEmpty()) holds.observe(task, observedRaw, receivedAt);
            // Only actual later quotes may extend a mixed minute. Late provider bars never rewrite it.
            else if (Boolean.TRUE.equals(observedRaw.get("available")) && (task == null || !task.running())
                    && (manual(config, task) || task == null || task.endedAt == null || QuoteState.time(observedRaw.get("timestamp")) > task.endedAt)) {
                BigDecimal price = ForexQuoteMarketService.controlledPrice(config, observedRaw, receivedAt);
                boolean manualRule = manual(config, task);
                // Before the first task, identity source prices are already retained in the frozen legacy OHLC.
                boolean sourceEquivalent = task == null && (config.getControlPriceOffset() == null || config.getControlPriceOffset().signum() == 0)
                    && price.compareTo(BigDecimal.valueOf(((Number)observedRaw.get("price")).doubleValue())) == 0;
                if (manualRule && !sourceEquivalent) store.manualPoint(config.getId(), receivedAt, price);
                else store.point(config.getId(), receivedAt, price, manualRule);
            }
            // Facts and the corresponding committed reader snapshot share this writer transaction.
            // Old/duplicate events returned above cannot renew execution validity or append manual points.
            pump(config,observedRaw,receivedAt,maxAgeMs);
            return null;
        });
    }
    public long sourceQuotes(List<TradingSymbol> configs, Map<String,Object> raw, long receivedAt) {
        return sourceQuotes(configs, raw, receivedAt, java.util.function.Function.identity());
    }
    long sourceQuotes(List<TradingSymbol> configs, Map<String,Object> raw, long receivedAt,
            java.util.function.Function<TradingSymbol,TradingSymbol> reload) {
        // Aliases sharing a source event commit together; a retry cannot partially duplicate history.
        configs.sort(Comparator.comparing(TradingSymbol::getId));
        List<Long> ids = new ArrayList<>();
        for (TradingSymbol config : configs) { TenantContext.require(config.getTenantId()); ids.add(config.getId()); }
        return store.withPlans(ids, () -> store.transaction(() -> {
            for (TradingSymbol config : configs) store.locked(config.getId(), () -> {
                TradingSymbol fresh = reload.apply(config);
                TenantContext.require(fresh.getTenantId());
                if (!Objects.equals(fresh.getId(), config.getId())) throw new IllegalStateException("Source alias identity changed");
                if (!RandomMarketPath.enabled(fresh)) sourceQuote(fresh, raw, receivedAt);
                return null;
            });
            if (configs.isEmpty()) return 0L;
            if (QuoteState.time(raw.get("timestamp")) < QuoteState.time(store.lastQuote(configs.get(0).getId()).get("timestamp"))) return 0L;
            List<Long> sequence = store.db.queryForList("SELECT event_sequence FROM market_source_event WHERE tenant_id=" + tenant()
                + " AND symbol_id=? AND event_id=?", Long.class, configs.get(0).getId(), raw.get("eventId"));
            return sequence.isEmpty() ? 0L : sequence.get(0);
        }));
    }
    /** FX subscriptions target real configured symbols, never hidden symbols or source-price history. */
    boolean conversionQuotes(List<TradingSymbol> configs,String code,String category,Map<String,Object> raw,long receivedAt,
            java.util.function.Function<TradingSymbol,TradingSymbol> reload) {
        configs.sort(Comparator.comparing(TradingSymbol::getId));
        return store.transaction(() -> {
            boolean accepted=false;
            for(TradingSymbol config:configs) {
                TenantContext.require(config.getTenantId());
                boolean committed=store.locked(config.getId(),() -> {
                    TradingSymbol fresh=reload.apply(config);
                    TenantContext.require(fresh.getTenantId());
                    if(!Objects.equals(fresh.getId(),config.getId())) throw new IllegalStateException("Conversion symbol identity changed");
                    return FundingConversions.record(store,fresh,code,category,raw,receivedAt);
                });
                accepted|=committed;
            }
            return accepted;
        });
    }
    public Map<String, Object> display(TradingSymbol config, Map<String, Object> raw, long now) {
        TenantContext.require(config.getTenantId());
        return store.runtime.read(config.getId(), false, now);
    }
    /** Exactly one bounded physical transaction per engine turn; consumers never call this. */
    public void pump(TradingSymbol config, Map<String,Object> raw,long now,long maxAge) { pump(config,raw,now,maxAge,true); }
    void pumpSource(TradingSymbol config,Map<String,Object> raw,long now,long maxAge) { pump(config,raw,now,maxAge,false); }
    private void pump(TradingSymbol config,Map<String,Object> raw,long now,long maxAge,boolean finalizeHistory) {
        locked(config.getId(), () -> {
            if(finalizeHistory)flows.finalizeOne(config.getId(),now);
            Map<String,Object> quote=displayLocked(config,raw,now);
            Task task=latest(config.getId());
            if(task!=null && task.running() && task.stopAt!=null && task.sampledUntil+1000>Math.min(task.stopAt,task.plannedEnd)) {
                stopAndHold(config.getId(),task.stopAt);quote=displayLocked(config,raw,task.stopAt);
                task=latest(config.getId());
            }
            boolean controlled=Boolean.TRUE.equals(config.getControlEnabled()) || Boolean.TRUE.equals(quote.get("controlRunning")) || Boolean.TRUE.equals(quote.get("controlHolding"));
            if((task==null || !task.running()) && Boolean.TRUE.equals(config.getControlEnabled()) && QuoteState.valid(raw))
                quote.put("price",ForexQuoteMarketService.controlledPrice(config,raw,now));
            boolean lag=task!=null && task.running() && task.sampledUntil+1000<=Math.min(now,task.stopAt==null?task.plannedEnd:task.stopAt);
            long expiry=controlled && !lag ? now+maxAge : QuoteState.time(raw.get("expiresAt"));
            if(expiry==0) expiry=QuoteState.time(raw.get("timestamp"))+maxAge;
            quote.put("controlActive",controlled);quote.put("executionExpiresAt",expiry);quote.put("expiresAt",expiry);
            boolean waitingSource=Arrays.asList("WAITING_SOURCE","HOLDING","RECOVERING","MANUAL").contains(quote.get("controlState")) && !Boolean.TRUE.equals(raw.get("available"));
            quote.put("available",!lag && !waitingSource && QuoteState.valid(quote) && (controlled || Boolean.TRUE.equals(raw.get("available"))));
            quote.put("tradeAvailable",quote.get("available"));quote.put("engineLag",lag);
            quote.put("stale",!Boolean.TRUE.equals(quote.get("available")));
            quote.put("status",Boolean.TRUE.equals(quote.get("available"))?"available":"unavailable");
            if(lag){quote.put("executionExpiresAt",0L);quote.put("status","engine_lag");}
            if(task!=null){quote.put("sampledUntil",task.sampledUntil);quote.put("plannedEnd",task.plannedEnd);quote.put("stopAt",task.stopAt);}
            Map<String,Object> status=new LinkedHashMap<>();statusLocked(config,status,raw,now,quote);
            status.put("executionExpiresAt",quote.get("executionExpiresAt"));status.put("tradeAvailable",quote.get("tradeAvailable"));
            // Current read under the same runtime lock; raw instrument refresh must retain committed FX companions.
            Map<String,Object> prior=store.db.queryForMap("SELECT quote_json FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=? FOR UPDATE",tenant(),config.getId());
            Map<String,Object> previous = prior.get("quote_json")==null ? Collections.emptyMap() : store.decode((String)prior.get("quote_json"));
            if(prior.get("quote_json")!=null) quote.put(FundingConversions.BOOK,FundingConversions.retain(previous,config));
            FundingQuoteAuthority.stamp(quote,config);
            // Queue receipt time can precede another committed engine turn. Candle and version
            // follow publication order under this lock, without changing source/control event time.
            long publishedAt=Math.max(now,QuoteState.time(previous.get("committedAt")));
            LiveKline.capture(store,config,quote,previous,publishedAt);
            final Map<String,Object> snapshotQuote=quote;
            store.runtime.phase(config.getId(),"snapshot",()->{store.runtime.snapshot(config.getId(),snapshotQuote,status,publishedAt);return null;});
            MarketQuoteCommitted.publish(events, tenant(), config.getSymbol());
            return null;
        });
    }
    private Map<String, Object> displayLocked(TradingSymbol config, Map<String, Object> raw, long now) {
        advance(latest(config.getId()), now);
        Map<String, Object> result = new HashMap<>(raw);
        Task task = latest(config.getId());
        boolean sourceAvailable = Boolean.TRUE.equals(raw.get("available"));
        result.put("sourceAvailable", sourceAvailable); result.put("tradeAvailable", sourceAvailable);
        result.put("sourceStatus", raw.get("status")); result.put("sourceTimestamp", raw.get("sourceTimestamp"));
        if (!sourceAvailable) {
            Map<String, Object> saved = store.lastQuote(config.getId());
            if (!saved.isEmpty() && QuoteState.time(saved.get("timestamp")) > QuoteState.time(result.get("sourceTimestamp"))) {
                result.put("price", saved.get("price")); result.put("timestamp", saved.get("timestamp"));
                result.put("sourceTimestamp", saved.get("timestamp")); result.put("fetchedAt", 0L);
            }
        }
        boolean hasHistory = task != null || !store.db.queryForList("SELECT minute_at FROM market_mixed_minute WHERE tenant_id=" + tenant() + " AND symbol_id=? LIMIT 1", Long.class, config.getId()).isEmpty();
        result.put("controlPublicationRevision", "0:0");
        if (hasHistory || Boolean.TRUE.equals(config.getControlEnabled())) result.put("controlHistory", true);
        if (task != null) {
            result.put("controlTaskId", task.id);
            Map<String,Object> publicationVersion = store.db.queryForMap("SELECT COUNT(*) AS n,COALESCE(SUM(p.to_at),0) AS total FROM market_control_publication p JOIN market_control_task t ON t.tenant_id=p.tenant_id AND t.id=p.task_id WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=?", config.getId());
            result.put("controlPublicationRevision", publicationVersion.get("n") + ":" + publicationVersion.get("total"));
            result.put("controlHistoryRevision", task.id + ":" + publicationVersion.get("n") + ":" + publicationVersion.get("total"));
        }
        // A committed manual rule supersedes an ended task, including its retained SOURCE flow.
        // controlRunning denotes an active display rule; admin running remains false for manual mode.
        if (manual(config, task)) {
            if (result.get("price") instanceof Number) result.put("price", ForexQuoteMarketService.controlledPrice(config, result, now));
            result.put("controlState", "MANUAL"); result.put("controlRunning", true);
            result.put("controlOffset", config.getControlPriceOffset());
            result.put("controlHistoryRevision", (task == null ? "" : result.get("controlHistoryRevision") + ":") + "manual:" + config.getRowVersion());
            result.put("displayAvailable", QuoteState.valid(result));
            return result;
        }
        if (task == null) return result;
        Map<String,Object> flow = flows.observe(task, raw, now);
        if (!flow.isEmpty() && !"TARGET".equals(flow.get("state"))) {
            String state = (String)flow.get("state");
            result.put("controlHistoryRevision", result.get("controlHistoryRevision") + ":" + state);
            result.put("controlState", state); result.put("controlRunning", !"SOURCE".equals(state));
            result.put("controlSourceResumed", "SOURCE".equals(state));
            result.put("controlHolding", "HOLDING".equals(state));
            if ("SOURCE".equals(state) && RandomMarketPath.enabled(config)) result.put("price", RandomMarketPath.price(config, now));
            if (!"SOURCE".equals(state)) {
                result.put("price", flow.get("last_price")); result.put("timestamp", flow.get("last_at"));
                result.put("generatedAt", flow.get("last_at")); result.put("displayAvailable", true);
                if (sourceAvailable && raw.get("price") instanceof Number) result.put("controlOffset", ControlHistoryStore.number(flow.get("last_price")).subtract(ControlHistoryStore.number(raw.get("price"))));
            }
            return result;
        }
        Map<String,Object> hold = holds.observe(task, raw, now);
        if (!hold.isEmpty()) {
            result.put("controlState", "HOLDING"); result.put("controlHolding", true);
            result.put("controlOffset", hold.get("offset_price")); result.put("controlRunning", true);
            result.put("price", hold.get("last_price")); result.put("timestamp", hold.get("generated_at"));
            result.put("generatedAt", hold.get("generated_at")); result.put("displayAvailable", true);
            return result;
        }
        if(!task.running() && task.stopAt!=null) {
            result.put("controlState","SOURCE");result.put("controlRunning",false);result.put("controlSourceResumed",true);result.put("controlOffset",BigDecimal.ZERO);
            return result;
        }
        boolean running = task.running();
        result.put("controlState", running ? "RUNNING" : sourceAvailable ? "SOURCE" : "WAITING_SOURCE");
        if (!running && Boolean.TRUE.equals(config.getControlEnabled()) && !PriceControlPath.running(config)) return result;
        List<Map<String, Object>> resumed = running ? Collections.emptyList()
            : store.db.queryForList("SELECT resumed_at,source_time,price FROM market_control_resume WHERE tenant_id=" + tenant() + " AND task_id=?", task.id);
        if (!running && sourceAvailable && resumed.isEmpty()) {
            locked(config.getId(), () -> {
                // A task can finish between provider polls. Persist the return to its currently valid
                // quote separately, without changing that quote's original timestamp or freshness.
                if (Objects.equals(task.id, latest(config.getId()).id)
                        && store.db.queryForObject("SELECT COUNT(*) FROM market_control_resume WHERE tenant_id=" + tenant() + " AND task_id=?", Integer.class, task.id) == 0) {
                    long at = Math.max(now, task.sampledUntil + 1);
                    store.db.update("INSERT INTO market_control_resume(tenant_id,task_id,resumed_at,source_time,price) VALUES(" + tenant() + ",?,?,?,?)",
                        task.id, at, QuoteState.time(raw.get("sourceTimestamp")), raw.get("price"));
                    store.point(config.getId(), at, ControlHistoryStore.number(raw.get("price")), false);
                }
                return null;
            });
            resumed = store.db.queryForList("SELECT resumed_at,source_time,price FROM market_control_resume WHERE tenant_id=" + tenant() + " AND task_id=?", task.id);
        }
        if (!running && !resumed.isEmpty()) {
            result.put("controlSourceResumed", true);
            Map<String, Object> resume = resumed.get(0);
            if (!(result.get("price") instanceof Number) || QuoteState.time(result.get("sourceTimestamp")) < ((Number) resume.get("source_time")).longValue()) {
                result.put("price", resume.get("price")); result.put("timestamp", resume.get("source_time"));
                result.put("sourceTimestamp", resume.get("source_time"));
            }
            return result;
        }
        // Once a later source quote has resumed, a future outage must not resurrect an old target.
        if (!running && task.endedAt != null && QuoteState.time(result.get("sourceTimestamp")) > task.endedAt) return result;
        if (running || !sourceAvailable) {
            // Read only committed samples: a database failure cannot display unpersisted history.
            long generated = task.sampledUntil;
            result.put("price", task.price(generated)); result.put("timestamp", generated);
            result.put("generatedAt", generated); result.put("displayAvailable", true);
            result.put("available", sourceAvailable); result.put("status", sourceAvailable ? "available" : "unavailable");
            result.put("controlRunning", running);
        }
        return result;
    }
    public void status(TradingSymbol config, Map<String,Object> result,Map<String,Object> raw,long now) {
        TenantContext.require(config.getTenantId());result.putAll(store.runtime.read(config.getId(),true,now));
    }
    private void statusLocked(TradingSymbol config, Map<String, Object> result, Map<String, Object> raw, long now,Map<String,Object> display) {
        TenantContext.require(config.getTenantId());
        Task task = latest(config.getId());
        boolean manual = manual(config, task);
        Map<String, Object> basis = startBasis(config, raw, display.get("price") instanceof Number ? ControlHistoryStore.number(display.get("price")) : null, now);
        if (task != null && task.holding) {
            basis.put("price", display.get("price")); basis.put("source", "CONTROL_DISPLAY");
            basis.put("timestamp", display.get("generatedAt"));
        }
        result.put("sourceAvailable", Boolean.TRUE.equals(raw.get("available"))); result.put("canStart", !basis.isEmpty());
        result.put("sourceEventAt",raw.get("sourceTimestamp"));result.put("lastSourceReceivedAt",raw.getOrDefault("sourceReceivedAt",raw.get("fetchedAt")));
        result.put("startBasis", basis); result.put("controlState", display.get("controlState")); result.put("currentPrice", display.get("price"));
        result.put("enabled",Boolean.TRUE.equals(config.getControlEnabled()));result.put("running",false);result.put("restoring",false);
        result.put("available",Boolean.TRUE.equals(display.get("available")));result.put("rawPrice",raw.get("price"));
        result.put("offset",display.get("controlOffset")==null ? config.getControlPriceOffset() : display.get("controlOffset"));
        if (task == null || manual) return;
        result.put("taskId", task.id); result.put("running", task.running() && now < task.plannedEnd);
        result.put("sampledUntil",task.sampledUntil);result.put("plannedEnd",task.plannedEnd);result.put("stopAt",task.stopAt);
        Map<String,Object> progressFlow=flows.get(task.id);
        long watermark=progressFlow.isEmpty() || "TARGET".equals(progressFlow.get("state"))?task.sampledUntil:QuoteState.time(progressFlow.get("last_at"));
        Map<String,Object> previous=store.runtime.read(config.getId(),true,now);
        boolean progressed=!Objects.equals(task.id,previous.get("taskId")) || watermark!=QuoteState.time(previous.get("controlProgressWatermark"));
        result.put("controlProgressWatermark",watermark);result.put("progressStartedAt",task.startedAt);result.put("progressEndAt",task.plannedEnd);
        result.put("lastControlProgressAt",progressed?System.currentTimeMillis():previous.get("lastControlProgressAt"));
        result.put("sourceEventAt",raw.get("sourceTimestamp"));result.put("lastSourceReceivedAt",raw.getOrDefault("sourceReceivedAt",raw.get("fetchedAt")));
        result.put("historyFinalizationPending",store.db.queryForObject("SELECT COUNT(*) FROM market_control_flow f JOIN market_control_task t ON t.tenant_id=f.tenant_id AND t.id=f.task_id WHERE f.tenant_id=? AND t.symbol_id=? AND f.history_pending_until IS NOT NULL",Integer.class,tenant(),config.getId())>0);
        result.put("enabled", task.running() || task.holding); result.put("holding", task.holding); result.put("restoring", "RESTORE".equals(task.kind));
        if (display.get("controlOffset") != null) result.put("offset", display.get("controlOffset"));
        result.put("startPrice", task.startPrice); result.put("targetPrice", task.targetPrice);
        result.put("durationSeconds", task.durationSeconds); result.put("intensity", task.intensity); result.put("randomOscillation", task.oscillation);
        result.put("algorithmVersion", task.algorithmVersion);
        if ((task.algorithmVersion == BalancedControlPlan.VERSION || task.algorithmVersion == StabilizedControlPlan.VERSION) && !"SOURCE".equals(progressFlow.get("state")) && !"STOPPED".equals(task.status)) {
            TargetControlPlan plan = store.plan(task.id);
            Map<String, Object> metadata = plan.snapshot();
            result.put("minStepAmount", new BigDecimal((String) metadata.get("minStep")).movePointLeft(plan.precision()).toPlainString());
            result.put("maxStepAmount", new BigDecimal((String) metadata.get("maxStep")).movePointLeft(plan.precision()).toPlainString());
            result.put("planSummary", plan.summary());
            if (task.algorithmVersion == StabilizedControlPlan.VERSION)
                for (String key : Arrays.asList("stepFormula", "deviationBandMode", "deviationBandPercent", "bandBasisPrice", "typicalAmount", "corridorAmount"))
                    result.put(key, metadata.get(key));
        }
        result.put("startedAt", task.startedAt); result.put("completedAt", task.endedAt);
        result.put("startSource", task.startSource); result.put("sourceTime", task.sourceTime);
        result.put("remainingSeconds", task.running() ? Math.max(0, (task.plannedEnd - now + 999) / 1000) : 0);
        Map<String,Object> flow = flows.get(task.id);
        if (!flow.isEmpty()) {
            result.putAll(store.decode((String)flow.get("options_json")));
            String state = (String)flow.get("state");
            result.put("running", "TARGET".equals(state) || "RECOVERING".equals(state) || "WAITING_SOURCE".equals(state));
            result.put("enabled", !"SOURCE".equals(state)); result.put("holding", "HOLDING".equals(state));
            result.put("restoring", "RECOVERING".equals(state) || "WAITING_SOURCE".equals(state));
            if ("RECOVERING".equals(state)) {
                Map<String,Object> options = store.decode((String)flow.get("options_json"));
                long duration = flow.get("remaining_millis") == null ? ((Number)options.get("restoreDurationSeconds")).longValue()*1000 : ((Number)flow.get("remaining_millis")).longValue();
                long end = ((Number)flow.get("recovery_started_at")).longValue() + duration;
                result.put("recoveryStartedAt",flow.get("recovery_started_at"));result.put("recoveryExpectedEnd",end);
                result.put("progressStartedAt",flow.get("recovery_started_at"));result.put("progressEndAt",end);
                result.put("remainingSeconds", Math.max(0, (end-now+999)/1000));
            }
        }
    }
}
