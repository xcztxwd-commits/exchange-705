package com.gtcfesk.exchange.market;

import java.math.*;
import java.util.*;

/** One durable flow per task. All calls run under the existing symbol-row transaction lock. */
final class ControlRecoveryFlow {
    private final ControlHistoryStore store;
    private final ControlHoldService holds;
    private final long openedAt = System.currentTimeMillis();
    ControlRecoveryFlow(ControlHistoryStore store, ControlHoldService holds) { this.store = store; this.holds = holds; }
    Map<String,Object> get(String id) {
        List<Map<String,Object>> rows = store.db.queryForList("SELECT * FROM market_control_flow WHERE task_id=?", id);
        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }
    void create(PersistentPriceControl.Task t, RecoveryOptions options) {
        store.db.update("INSERT INTO market_control_flow(task_id,options_json,state,last_price,last_at) VALUES(?,?,'TARGET',?,?)",
            t.id, store.encode(options.snapshot()), t.startPrice, t.startedAt);
    }
    void publish(PersistentPriceControl.Task t, long until) {
        store.db.update("INSERT INTO market_control_publication(task_id,published_at,from_at,to_at) VALUES(?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE to_at=GREATEST(to_at,VALUES(to_at))", t.id, System.currentTimeMillis(), t.startedAt, until);
    }
    void cancel(PersistentPriceControl.Task t, long now, boolean hold) {
        if (t == null) return;
        Map<String,Object> f = get(t.id);
        if (f.isEmpty() || "SOURCE".equals(f.get("state"))) return;
        if (Boolean.TRUE.equals(store.decode((String) f.get("options_json")).get("autoReplaceHistory")))
            publish(t, Math.max(t.sampledUntil, ((Number)f.get("last_at")).longValue()));
        store.db.update("UPDATE market_control_flow SET state=?,finished_at=? WHERE task_id=?",
            hold ? "HOLDING" : "SOURCE", hold ? null : now, t.id);
    }
    private void pause(PersistentPriceControl.Task t, Map<String,Object> f, Map<String,Object> options) {
        long remaining = f.get("remaining_millis") == null ? ((Number)options.get("restoreDurationSeconds")).longValue()*1000 : ((Number)f.get("remaining_millis")).longValue();
        long elapsed = Math.max(0, ((Number)f.get("last_at")).longValue() - ((Number)f.get("recovery_started_at")).longValue());
        store.db.update("UPDATE market_control_flow SET state='WAITING_SOURCE',remaining_millis=? WHERE task_id=?", Math.max(1,remaining-elapsed), t.id);
    }
    Map<String,Object> observe(PersistentPriceControl.Task t, Map<String,Object> raw, long now) {
        Map<String,Object> f = get(t.id);
        if (f.isEmpty()) return f;
        Map<String,Object> options = store.decode((String) f.get("options_json"));
        String state = (String) f.get("state");
        // A fresh process must not count its unobserved downtime as recovery progress.
        if ("RECOVERING".equals(state) && ((Number)f.get("last_at")).longValue() < openedAt) {
            pause(t, f, options); f = get(t.id); state = "WAITING_SOURCE";
        }
        boolean available = Boolean.TRUE.equals(raw.get("available")) && raw.get("price") instanceof Number
            && ControlHistoryStore.number(raw.get("price")).signum() > 0;
        if ("SOURCE".equals(state)) return f;
        if ("TARGET".equals(state)) {
            if (t.running()) return f;
            if (Boolean.TRUE.equals(options.get("autoReplaceHistory"))) publish(t, t.endedAt);
            state = Boolean.TRUE.equals(options.get("autoRestore")) ? "WAITING_SOURCE" : "HOLDING";
            store.db.update("UPDATE market_control_flow SET state=?,last_price=?,last_at=? WHERE task_id=?",
                state, t.price(t.sampledUntil), t.sampledUntil, t.id);
            f = get(t.id);
        }
        if ("HOLDING".equals(state)) {
            Map<String,Object> h = holds.observe(t, raw, now);
            if (!h.isEmpty()) store.db.update("UPDATE market_control_flow SET last_price=?,last_at=? WHERE task_id=?",
                h.get("last_price"), h.get("generated_at"), t.id);
            return get(t.id);
        }
        if (!available) {
            if ("RECOVERING".equals(state)) {
                pause(t, f, options);
                return get(t.id);
            }
            return f; // Never synthesize outage samples or consume unobserved recovery time.
        }
        BigDecimal source = ControlHistoryStore.number(raw.get("price"));
        if ("WAITING_SOURCE".equals(state)) {
            holds.release(t.id, now);
            BigDecimal last = ControlHistoryStore.number(f.get("last_price"));
            store.db.update("UPDATE market_control_flow SET state='RECOVERING',recovery_started_at=?,recovery_offset=? WHERE task_id=?",
                now, last.subtract(source), t.id);
            f = get(t.id);
        }
        long start = ((Number)f.get("recovery_started_at")).longValue();
        long duration = f.get("remaining_millis") == null ? ((Number)options.get("restoreDurationSeconds")).longValue() * 1000 : ((Number)f.get("remaining_millis")).longValue();
        double progress = "QUICK".equals(options.get("restoreMode")) ? 1 : Math.min(1, Math.max(0, (now - start) / (double)duration));
        BigDecimal offset = ControlHistoryStore.number(f.get("recovery_offset"));
        double wave = Boolean.TRUE.equals(options.get("restoreRandomOscillation"))
            ? new Random(t.id.hashCode() ^ ((now - start) / 1000)).nextDouble() * 2 - 1 : Math.sin((now - start) / 1000.0 * Math.PI / 2);
        double amplitude = ((Number)options.get("restoreIntensity")).intValue() * .01 * Math.sin(Math.PI * progress) * (1 - progress);
        BigDecimal price = source.add(offset.multiply(BigDecimal.valueOf(1 - progress)))
            .add(offset.abs().multiply(BigDecimal.valueOf(wave * amplitude)))
            .max(BigDecimal.ONE.movePointLeft(t.pricePrecision)).setScale(t.pricePrecision, RoundingMode.HALF_UP);
        long lastAt = ((Number)f.get("last_at")).longValue();
        if (now < lastAt) return f;
        if (now == lastAt && price.compareTo(ControlHistoryStore.number(f.get("last_price"))) != 0) now++;
        if (now > lastAt) store.generatedPoints(t.id, t.symbolId, Collections.singletonList(new ControlHistoryStore.PricePoint(now, price)));
        if (now >= lastAt) store.db.update("UPDATE market_control_flow SET state=?,last_price=?,last_at=?,finished_at=? WHERE task_id=?",
            progress >= 1 ? "SOURCE" : "RECOVERING", price, now, progress >= 1 ? now : null, t.id);
        if (progress >= 1) {
            if (Boolean.TRUE.equals(options.get("autoReplaceHistory"))) publish(t, now);
            store.db.update("INSERT INTO market_control_resume(task_id,resumed_at,source_time,price) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE task_id=VALUES(task_id)",
                t.id, now, QuoteState.time(raw.get("sourceTimestamp")), source);
        }
        return get(t.id);
    }
}
