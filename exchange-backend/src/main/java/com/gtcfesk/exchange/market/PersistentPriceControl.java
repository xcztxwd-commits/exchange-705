package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import lombok.Getter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/** Target lifecycle is independent of provider availability and the mutable TradingSymbol settings. */
@Service
public class PersistentPriceControl {
    private final ControlHistoryStore store;
    private final ControlHoldService holds;
    public PersistentPriceControl(ControlHistoryStore store) { this.store = store; this.holds = new ControlHoldService(store); }
    @Getter public static class Task {
        String id, symbol, kind, status, startSource;
        long symbolId, sourceTime, startedAt, plannedEnd, sampledUntil;
        Long endedAt;
        Long historyReplacedAt;
        boolean holding;
        BigDecimal startPrice, targetPrice;
        int durationSeconds, intensity, pricePrecision, algorithmVersion;
        boolean oscillation;
        boolean running() { return "RUNNING".equals(status); }
        TradingSymbol path() {
            if (algorithmVersion != 1 && algorithmVersion != 2) throw new IllegalStateException("Unsupported control algorithm " + algorithmVersion);
            TradingSymbol p = new TradingSymbol(); p.setSymbol(symbol); p.setPricePrecision(pricePrecision);
            p.setControlStartPrice(startPrice); p.setControlTargetPrice(targetPrice); p.setControlStartedAt(startedAt);
            p.setControlDurationSeconds(durationSeconds); p.setControlIntensity(intensity); p.setControlRandomOscillation(oscillation);
            return p;
        }
        BigDecimal price(long time) { return PriceControlPath.price(path(), time, algorithmVersion); }
    }
    private static final String TASK_SELECT = "SELECT t.*,h.activated_at,h.released_at,p.published_at FROM market_control_task t "
        + "LEFT JOIN market_control_hold h ON h.task_id=t.id LEFT JOIN market_control_publication p ON p.task_id=t.id ";
    private static final RowMapper<Task> TASK = (r, n) -> {
        Task t = new Task(); t.id = r.getString("id"); t.symbolId = r.getLong("symbol_id"); t.symbol = r.getString("symbol");
        t.kind = r.getString("kind"); t.status = r.getString("status"); t.startSource = r.getString("start_source");
        t.sourceTime = r.getLong("source_time"); t.startedAt = r.getLong("started_at"); t.plannedEnd = r.getLong("planned_end");
        t.sampledUntil = r.getLong("sampled_until"); t.endedAt = (Long) r.getObject("ended_at");
        t.startPrice = r.getBigDecimal("start_price"); t.targetPrice = r.getBigDecimal("target_price");
        t.durationSeconds = r.getInt("duration_seconds"); t.intensity = r.getInt("intensity");
        t.pricePrecision = r.getInt("price_precision"); t.algorithmVersion = r.getInt("algorithm_version");
        t.oscillation = r.getBoolean("oscillation");
        t.holding = r.getObject("activated_at") != null && r.getObject("released_at") == null;
        t.historyReplacedAt = (Long) r.getObject("published_at"); return t;
    };
    public Task latest(long symbol) {
        List<Task> rows = store.db.query(TASK_SELECT + "WHERE t.symbol_id=? ORDER BY t.started_at DESC,t.id DESC LIMIT 1", TASK, symbol);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public List<Task> history(long symbol, Long before) {
        return store.db.query(TASK_SELECT + "WHERE t.symbol_id=? AND t.started_at<? ORDER BY t.started_at DESC LIMIT 100", TASK,
            symbol, before == null ? Long.MAX_VALUE : before);
    }
    public List<Long> runningSymbols() {
        return store.db.queryForList("SELECT DISTINCT t.symbol_id FROM market_control_task t LEFT JOIN market_control_hold h ON h.task_id=t.id WHERE t.status='RUNNING' OR (h.activated_at IS NOT NULL AND h.released_at IS NULL)", Long.class);
    }
    public Task replaceHistory(long symbol, String taskId) {
        return store.locked(symbol, () -> {
            advance(latest(symbol), System.currentTimeMillis());
            List<Task> tasks = store.db.query(TASK_SELECT + "WHERE t.id=? AND t.symbol_id=?", TASK, taskId, symbol);
            if (tasks.isEmpty()) throw new BusinessException("控盘任务不存在");
            Task task = tasks.get(0);
            if (task.running() || task.endedAt == null) throw new BusinessException("目标轨迹结束后才能替代历史行情");
            // Publish the original interval. The existing authoritative mixed minutes already preserve
            // its source snapshot and actual later events; copying whole candles would erase those events.
            store.db.update("INSERT INTO market_control_publication(task_id,published_at,from_at,to_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE task_id=VALUES(task_id)",
                task.id, System.currentTimeMillis(), task.startedAt, task.endedAt);
            return store.db.queryForObject(TASK_SELECT + "WHERE t.id=?", TASK, task.id);
        });
    }
    public void importLegacy(TradingSymbol config) {
        if (!PriceControlPath.running(config)) return;
        store.locked(config.getId(), () -> {
            String key = "legacy-" + config.getControlStartedAt();
            if (store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE symbol_id=? AND request_key=?", Integer.class, config.getId(), key) == 0) {
                store.db.update("INSERT INTO market_control_task(id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until,request_key) VALUES(?,?,?,1,'TARGET','RUNNING',?,?,?,?,?,?,'LEGACY_PARAMETERS',?,?,?,?,?)",
                    UUID.randomUUID().toString(), config.getId(), config.getSymbol(), config.getControlStartPrice(), config.getControlTargetPrice(),
                    config.getControlDurationSeconds(), config.getControlIntensity(), Boolean.TRUE.equals(config.getControlRandomOscillation()), PriceControlPath.precision(config),
                    config.getControlStartedAt(), config.getControlStartedAt(), PriceControlPath.endsAt(config), config.getControlStartedAt() - 1000, key);
            }
            return null;
        });
    }
    public void advance(long symbol, long now) { store.locked(symbol, () -> { advance(latest(symbol), now); return null; }); }
    private void advance(Task task, long now) {
        if (task == null || !task.running()) return;
        TradingSymbol path = task.path();
        long until = Math.min(now, task.plannedEnd);
        List<ControlHistoryStore.PricePoint> points = new ArrayList<>();
        // Include both endpoints. Samples follow start+n seconds, not wall-clock rounding.
        for (long time = task.sampledUntil < task.startedAt ? task.startedAt : task.sampledUntil + 1000; time <= until; time += 1000) {
            BigDecimal price = PriceControlPath.price(path, time, task.algorithmVersion);
            points.add(new ControlHistoryStore.PricePoint(time, price));
            task.sampledUntil = time;
        }
        store.generatedPoints(task.id, task.symbolId, points);
        if (now >= task.plannedEnd) { task.status = "COMPLETED"; task.endedAt = task.plannedEnd; holds.activate(task); }
        store.db.update("UPDATE market_control_task SET sampled_until=?,status=?,ended_at=? WHERE id=?",
            task.sampledUntil, task.status, task.endedAt, task.id);
    }
    public Map<String, Object> startBasis(TradingSymbol config, Map<String, Object> raw, BigDecimal displayed, long now) {
        Map<String, Object> basis = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(raw.get("available")) && displayed != null && displayed.signum() > 0) {
            basis.put("price", displayed); basis.put("source", "LIVE_DISPLAY"); basis.put("timestamp", raw.get("sourceTimestamp"));
        } else {
            Map<String, Object> candle = store.lastClose(config.getId(), now);
            // A completed mixed minute is also historical price, and wins over its original minute.
            List<Map<String, Object>> mixed = store.db.query("SELECT body FROM market_mixed_minute WHERE symbol_id=? AND minute_at+60000<=? ORDER BY minute_at DESC LIMIT 1",
                (rs, n) -> store.decode(rs.getString(1)), config.getId(), now);
            if (!mixed.isEmpty() && (candle.isEmpty() || ControlHistoryStore.time(mixed.get(0)) + 60000
                    >= ControlHistoryStore.time(candle) + RandomMarketPath.duration(String.valueOf(candle.get("period"))))) candle = mixed.get(0);
            if (!candle.isEmpty()) {
                basis.put("price", candle.get("close_price")); basis.put("source", "COMPLETED_CANDLE");
                basis.put("timestamp", ControlHistoryStore.time(candle) + RandomMarketPath.duration(String.valueOf(candle.getOrDefault("period", "1m"))));
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
    public Task start(TradingSymbol config, Map<String, Object> raw, BigDecimal displayed, int duration, BigDecimal target,
            int intensity, boolean oscillation, boolean restore, String requestKey) {
        return store.locked(config.getId(), () -> {
            if (requestKey != null) {
                if (requestKey.length() > 64 || requestKey.trim().isEmpty()) throw new BusinessException("任务请求标识无效");
                List<Task> previous = store.db.query(TASK_SELECT + "WHERE t.symbol_id=? AND t.request_key=?", TASK, config.getId(), requestKey);
                if (!previous.isEmpty()) {
                    Task task = previous.get(0);
                    if (task.durationSeconds != duration || task.intensity != intensity || task.oscillation != oscillation
                            || !task.kind.equals(restore ? "RESTORE" : "TARGET") || !restore && task.targetPrice.compareTo(target) != 0)
                        throw new BusinessException("任务请求标识已用于不同参数");
                    return task;
                }
            }
            long now = System.currentTimeMillis();
            Task old = latest(config.getId()); advance(old, now);
            if (old != null) now = Math.max(now, Math.max(old.startedAt, old.sampledUntil) + 1);
            if (old != null && old.running() && !restore) throw new BusinessException("自动控盘正在运行，请先停止任务");
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
                basis.put("price", old != null && old.running() ? old.price(old.sampledUntil) : startingDisplay);
                basis.put("source", "CONTROL_DISPLAY");
                basis.put("timestamp", old != null && old.running() ? old.sampledUntil
                    : hold.isEmpty() ? raw.get("sourceTimestamp") : hold.get("generated_at"));
                stopLocked(old, now);
            }
            if (basis.get("price") == null || ControlHistoryStore.number(basis.get("price")).signum() <= 0)
                throw new BusinessException("没有有效历史价格，无法启动目标控盘");
            String id = UUID.randomUUID().toString();
            if (old != null) holds.release(old.id, now);
            store.freeze(config.getId(), now);
            store.db.update("INSERT INTO market_control_task(id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until,request_key) VALUES(?,?,?,2,?,'RUNNING',?,?,?,?,?,?,?,?,?,?,?,?)",
                id, config.getId(), config.getSymbol(), restore ? "RESTORE" : "TARGET", basis.get("price"), target, duration, intensity, oscillation,
                PriceControlPath.precision(config), basis.get("source"), QuoteState.time(basis.get("timestamp")), now, now + duration * 1000L, now - 1000, requestKey);
            Task task = latest(config.getId());
            if (!restore) holds.prepare(task, raw);
            advance(task, now); return task;
        });
    }
    public void stop(long symbol, long now) { store.locked(symbol, () -> { stopLocked(latest(symbol), now); return null; }); }
    /** Stop movement without restoring the source price; only an explicit restore releases the offset. */
    public void stopAndHold(long symbol, long now) {
        store.locked(symbol, () -> {
            Task task = latest(symbol); advance(task, now);
            if (task != null && task.running()) {
                if (holds.active(task.id).isEmpty()) {
                    // Restore tasks have no pending hold; their interrupted price must also be retained.
                    if (store.db.queryForObject("SELECT COUNT(*) FROM market_control_hold WHERE task_id=?", Integer.class, task.id) == 0)
                        holds.prepare(task, store.lastQuote(symbol));
                    holds.activate(task, now, task.price(task.sampledUntil));
                }
                store.db.update("UPDATE market_control_task SET status='STOPPED',ended_at=? WHERE id=?", now, task.id);
            }
            return null;
        });
    }
    private void stopLocked(Task task, long now) {
        advance(task, now);
        if (task != null) holds.release(task.id, now);
        if (task != null && task.running()) {
            store.db.update("UPDATE market_control_task SET status='STOPPED',ended_at=? WHERE id=?", now, task.id);
        }
    }
    public void sourceQuote(TradingSymbol config, Map<String, Object> raw, long receivedAt) {
        store.locked(config.getId(), () -> {
            if (!store.quote(config.getId(), raw, receivedAt)) return null;
            Task task = latest(config.getId()); advance(task, receivedAt);
            if (task != null && !holds.active(task.id).isEmpty()) { holds.observe(task, raw, receivedAt); return null; }
            // Only actual later quotes may extend a mixed minute. Late provider bars never rewrite it.
            if (Boolean.TRUE.equals(raw.get("available")) && (task == null || !task.running())
                    && (task == null || task.endedAt == null || QuoteState.time(raw.get("timestamp")) > task.endedAt))
                store.point(config.getId(), receivedAt, ForexQuoteMarketService.controlledPrice(config, raw, receivedAt), Boolean.TRUE.equals(config.getControlEnabled()));
            if (task != null && !task.running() && Boolean.TRUE.equals(raw.get("available"))) display(config, raw, receivedAt);
            return null;
        });
    }
    public void sourceQuotes(List<TradingSymbol> configs, Map<String,Object> raw, long receivedAt) {
        // Aliases sharing a source event commit together; a retry cannot partially duplicate history.
        configs.sort(Comparator.comparing(TradingSymbol::getId));
        store.transaction(() -> {
            for (TradingSymbol config : configs) sourceQuote(config, raw, receivedAt);
            return null;
        });
    }
    public Map<String, Object> display(TradingSymbol config, Map<String, Object> raw, long now) {
        advance(config.getId(), now);
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
        boolean hasHistory = task != null || !store.db.queryForList("SELECT minute_at FROM market_mixed_minute WHERE symbol_id=? LIMIT 1", Long.class, config.getId()).isEmpty();
        if (hasHistory || Boolean.TRUE.equals(config.getControlEnabled())) result.put("controlHistory", true);
        if (task == null) return result;
        result.put("controlHistory", true); result.put("controlTaskId", task.id);
        Map<String,Object> hold = holds.observe(task, raw, now);
        if (!hold.isEmpty()) {
            result.put("controlState", "HOLDING"); result.put("controlHolding", true);
            result.put("controlOffset", hold.get("offset_price")); result.put("controlRunning", true);
            result.put("price", hold.get("last_price")); result.put("timestamp", hold.get("generated_at"));
            result.put("generatedAt", hold.get("generated_at")); result.put("displayAvailable", true);
            return result;
        }
        boolean running = task.running() && now < task.plannedEnd;
        result.put("controlState", running ? "RUNNING" : sourceAvailable ? "SOURCE" : "WAITING_SOURCE");
        if (!running && Boolean.TRUE.equals(config.getControlEnabled()) && !PriceControlPath.running(config)) return result;
        List<Map<String, Object>> resumed = running ? Collections.emptyList()
            : store.db.queryForList("SELECT resumed_at,source_time,price FROM market_control_resume WHERE task_id=?", task.id);
        if (!running && sourceAvailable && resumed.isEmpty()) {
            store.locked(config.getId(), () -> {
                // A task can finish between provider polls. Persist the return to its currently valid
                // quote separately, without changing that quote's original timestamp or freshness.
                if (Objects.equals(task.id, latest(config.getId()).id)
                        && store.db.queryForObject("SELECT COUNT(*) FROM market_control_resume WHERE task_id=?", Integer.class, task.id) == 0) {
                    long at = Math.max(now, task.sampledUntil + 1);
                    store.db.update("INSERT INTO market_control_resume(task_id,resumed_at,source_time,price) VALUES(?,?,?,?)",
                        task.id, at, QuoteState.time(raw.get("sourceTimestamp")), raw.get("price"));
                    store.point(config.getId(), at, ControlHistoryStore.number(raw.get("price")), false);
                }
                return null;
            });
            resumed = store.db.queryForList("SELECT resumed_at,source_time,price FROM market_control_resume WHERE task_id=?", task.id);
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
    public void status(TradingSymbol config, Map<String, Object> result, Map<String, Object> raw, long now) {
        Map<String, Object> display = display(config, raw, now);
        Task task = latest(config.getId());
        boolean manual = Boolean.TRUE.equals(config.getControlEnabled()) && !PriceControlPath.running(config) && (task == null || !task.running());
        if (manual && raw.get("price") instanceof Number) display.put("price", ForexQuoteMarketService.controlledPrice(config, raw, now));
        Map<String, Object> basis = startBasis(config, raw, display.get("price") instanceof Number ? ControlHistoryStore.number(display.get("price")) : null, now);
        if (task != null && task.holding) {
            basis.put("price", display.get("price")); basis.put("source", "CONTROL_DISPLAY");
            basis.put("timestamp", display.get("generatedAt"));
        }
        result.put("sourceAvailable", Boolean.TRUE.equals(raw.get("available"))); result.put("canStart", !basis.isEmpty());
        result.put("startBasis", basis); result.put("controlState", display.get("controlState")); result.put("currentPrice", display.get("price"));
        if (task == null || manual) return;
        result.put("taskId", task.id); result.put("running", task.running() && now < task.plannedEnd);
        result.put("enabled", task.running() || task.holding); result.put("holding", task.holding); result.put("restoring", "RESTORE".equals(task.kind));
        if (display.get("controlOffset") != null) result.put("offset", display.get("controlOffset"));
        result.put("startPrice", task.startPrice); result.put("targetPrice", task.targetPrice);
        result.put("durationSeconds", task.durationSeconds); result.put("intensity", task.intensity); result.put("randomOscillation", task.oscillation);
        result.put("startedAt", task.startedAt); result.put("completedAt", task.endedAt);
        result.put("startSource", task.startSource); result.put("sourceTime", task.sourceTime);
        result.put("remainingSeconds", task.running() ? Math.max(0, (task.plannedEnd - now + 999) / 1000) : 0);
    }
}
