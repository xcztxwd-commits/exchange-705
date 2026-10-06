package com.gtcfesk.exchange.market;

import static com.gtcfesk.exchange.market.ControlHistoryStore.tenant;

import java.math.*;
import java.util.*;

/** One durable flow per task. All calls run under the existing symbol-row transaction lock. */
final class ControlRecoveryFlow {
    private final ControlHistoryStore store;
    private final ControlHoldService holds;
    private final long openedAt = System.currentTimeMillis();
    ControlRecoveryFlow(ControlHistoryStore store, ControlHoldService holds) { this.store = store; this.holds = holds; }
    Map<String,Object> get(String id) {
        List<Map<String,Object>> rows = store.db.queryForList("SELECT * FROM market_control_flow WHERE tenant_id=" + tenant() + " AND task_id=?", id);
        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }
    void create(PersistentPriceControl.Task t, RecoveryOptions options) {
        store.db.update("INSERT INTO market_control_flow(tenant_id,task_id,options_json,state,last_price,last_at) VALUES(" + tenant() + ",?,?,'TARGET',?,?)",
            t.id, store.encode(options.snapshot()), t.startPrice, t.startedAt);
    }
    boolean publish(PersistentPriceControl.Task t, long until) {
        if (!store.historyOrdering.publicationNeeded(t.symbolId, t.id, t.startedAt, until)) return false;
        store.db.update("INSERT INTO market_control_publication(tenant_id,task_id,published_at,from_at,to_at) VALUES(" + tenant() + ",?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE to_at=GREATEST(to_at,VALUES(to_at))", t.id, System.currentTimeMillis(), t.startedAt, until);
        return true;
    }
    void cancel(PersistentPriceControl.Task t, long now, boolean hold) {
        if (t == null) return;
        Map<String,Object> f = get(t.id);
        if (f.isEmpty() || "SOURCE".equals(f.get("state"))) return;
        if (Boolean.TRUE.equals(store.decode((String) f.get("options_json")).get("autoReplaceHistory")))
            publish(t, Math.max(t.sampledUntil, ((Number)f.get("last_at")).longValue()));
        store.db.update("UPDATE market_control_flow SET state=?,finished_at=? WHERE tenant_id=" + tenant() + " AND task_id=?",
            hold ? "HOLDING" : "SOURCE", hold ? null : now, t.id);
    }
    /** Emergency only: enqueue publication of committed facts, never advance or activate a hold. */
    void source(PersistentPriceControl.Task t,long now) {
        Map<String,Object> f=get(t.id);
        if(f.isEmpty())return; // Legacy history already has always-visible semantics.
        long committed=Math.max(t.sampledUntil,((Number)f.get("last_at")).longValue());
        boolean publish=Boolean.TRUE.equals(store.decode((String)f.get("options_json")).get("autoReplaceHistory")) && committed>=t.startedAt;
        store.db.update("UPDATE market_control_flow SET state='SOURCE',finished_at=?,history_pending_until=?,history_retry_at=?,history_error=NULL WHERE tenant_id=? AND task_id=?",
            now,publish?committed:null,publish?now+1000:null,tenant(),t.id);
    }
    /** Existing engine lane retries one persisted finalization per turn. No source-ledger scan. */
    void finalizeOne(long symbol,long now) {
        if(!store.runtime.hasBudget(2000))return;
        List<Map<String,Object>> pending=store.db.queryForList("SELECT f.task_id,f.history_pending_until,t.started_at FROM market_control_flow f JOIN market_control_task t ON t.tenant_id=f.tenant_id AND t.id=f.task_id WHERE f.tenant_id=? AND t.symbol_id=? AND f.history_pending_until IS NOT NULL AND f.history_retry_at<=? ORDER BY f.history_retry_at LIMIT 1",tenant(),symbol,now);
        if(pending.isEmpty())return;
        Map<String,Object> row=pending.get(0);String id=(String)row.get("task_id");
        long until=((Number)row.get("history_pending_until")).longValue(),from=((Number)row.get("started_at")).longValue();
        try {
            if(store.historyOrdering.publicationNeeded(symbol,id,from,until))
                store.db.update("INSERT INTO market_control_publication(tenant_id,task_id,published_at,from_at,to_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE to_at=GREATEST(to_at,VALUES(to_at))",tenant(),id,now,from,until);
            store.db.update("UPDATE market_control_flow SET history_pending_until=NULL,history_retry_at=NULL,history_error=NULL WHERE tenant_id=? AND task_id=?",tenant(),id);
        } catch(com.gtcfesk.exchange.common.BusinessException protectedHistory) {
            if(protectedHistory.getMessage()==null || !protectedHistory.getMessage().startsWith("HISTORY_LEGACY_ORDER_PENDING"))throw protectedHistory;
            store.db.update("UPDATE market_control_flow SET history_retry_at=?,history_error='HISTORY_LEGACY_ORDER_PENDING' WHERE tenant_id=? AND task_id=?",now+60000,tenant(),id);
        }
    }
    private void pause(PersistentPriceControl.Task t, Map<String,Object> f, Map<String,Object> options) {
        long remaining = f.get("remaining_millis") == null ? ((Number)options.get("restoreDurationSeconds")).longValue()*1000 : ((Number)f.get("remaining_millis")).longValue();
        long elapsed = Math.max(0, ((Number)f.get("last_at")).longValue() - ((Number)f.get("recovery_started_at")).longValue());
        store.db.update("UPDATE market_control_flow SET state='WAITING_SOURCE',remaining_millis=? WHERE tenant_id=" + tenant() + " AND task_id=?", Math.max(1,remaining-elapsed), t.id);
    }
    Map<String,Object> observe(PersistentPriceControl.Task t, Map<String,Object> raw, long now) {
        Map<String,Object> f = get(t.id);
        if (f.isEmpty()) return f;
        Map<String,Object> options = store.decode((String) f.get("options_json"));
        String state = (String) f.get("state");
        // A fresh process or stalled scheduler must not consume unobserved downtime.
        if ("RECOVERING".equals(state) && (((Number)f.get("last_at")).longValue() < openedAt
                || now - ((Number)f.get("last_at")).longValue() > 2000)) {
            pause(t, f, options); f = get(t.id); state = "WAITING_SOURCE";
        }
        boolean available = Boolean.TRUE.equals(raw.get("available")) && raw.get("price") instanceof Number
            && ControlHistoryStore.number(raw.get("price")).signum() > 0;
        if ("SOURCE".equals(state)) return f;
        if ("TARGET".equals(state)) {
            if (t.running()) return f;
            if (Boolean.TRUE.equals(options.get("autoReplaceHistory"))) publish(t, t.endedAt);
            state = Boolean.TRUE.equals(options.get("autoRestore")) ? "WAITING_SOURCE" : "HOLDING";
            store.db.update("UPDATE market_control_flow SET state=?,last_price=?,last_at=? WHERE tenant_id=" + tenant() + " AND task_id=?",
                state, t.price(t.sampledUntil), t.sampledUntil, t.id);
            f = get(t.id);
        }
        if ("HOLDING".equals(state)) {
            Map<String,Object> h = holds.observe(t, raw, now);
            if (!h.isEmpty()) store.db.update("UPDATE market_control_flow SET last_price=?,last_at=? WHERE tenant_id=" + tenant() + " AND task_id=?",
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
        if(store.runtime.sampleAllowance(t.symbolId)==0)return f;
        BigDecimal source = ControlHistoryStore.number(raw.get("price"));
        if ("WAITING_SOURCE".equals(state)) {
            holds.release(t.id, now);
            BigDecimal last = ControlHistoryStore.number(f.get("last_price"));
            store.db.update("UPDATE market_control_flow SET state='RECOVERING',recovery_started_at=?,recovery_offset=? WHERE tenant_id=" + tenant() + " AND task_id=?",
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
        if (now >= lastAt) store.db.update("UPDATE market_control_flow SET state=?,last_price=?,last_at=?,finished_at=? WHERE tenant_id=" + tenant() + " AND task_id=?",
            progress >= 1 ? "SOURCE" : "RECOVERING", price, now, progress >= 1 ? now : null, t.id);
        if (progress >= 1) {
            if (Boolean.TRUE.equals(options.get("autoReplaceHistory"))) publish(t, now);
            store.db.update("INSERT INTO market_control_resume(tenant_id,task_id,resumed_at,source_time,price) VALUES(" + tenant() + ",?,?,?,?) ON DUPLICATE KEY UPDATE task_id=VALUES(task_id)",
                t.id, now, QuoteState.time(raw.get("sourceTimestamp")), source);
        }
        return get(t.id);
    }
}
