package com.gtcfesk.exchange.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** A completed target's offset follows observed source quotes; outages never manufacture candles. */
final class ControlHoldService {
    private final ControlHistoryStore store;
    public ControlHoldService(ControlHistoryStore store) { this.store = store; }

    Map<String,Object> active(String task) {
        List<Map<String,Object>> rows = store.db.queryForList(
            "SELECT * FROM market_control_hold WHERE task_id=? AND activated_at IS NOT NULL AND released_at IS NULL", task);
        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }
    void prepare(PersistentPriceControl.Task task, Map<String,Object> raw) {
        Map<String,Object> reference = store.lastQuote(task.symbolId);
        if (QuoteState.valid(raw) && QuoteState.time(raw.get("timestamp")) >= QuoteState.time(reference.get("timestamp"))) reference = raw;
        // With candles only, their recorded start basis is the explicit reference, never an invented quote.
        Object price = reference.getOrDefault("price", task.startPrice);
        long time = QuoteState.time(reference.getOrDefault("timestamp", task.sourceTime));
        store.db.update("INSERT INTO market_control_hold(task_id,reference_price,reference_time,last_price,generated_at,source_time) VALUES(?,?,?,?,?,?)",
            task.id, price, time, task.startPrice, task.startedAt, time);
    }
    void activate(PersistentPriceControl.Task task) {
        activate(task, task.plannedEnd, task.targetPrice);
    }
    void activate(PersistentPriceControl.Task task, long at, BigDecimal displayedPrice) {
        List<Map<String,Object>> pending = store.db.queryForList(
            "SELECT * FROM market_control_hold WHERE task_id=? AND activated_at IS NULL AND released_at IS NULL", task.id);
        if (pending.isEmpty()) return;
        Map<String,Object> reference = pending.get(0);
        List<Map<String,Object>> ticks = store.db.queryForList(
            "SELECT price,source_time FROM (" + ControlHistoryStore.sourceEvents() + ") ticks WHERE symbol_id=? AND received_at<=? AND source_time<=? ORDER BY source_time DESC,received_at DESC,event_sequence DESC LIMIT 1",
            task.symbolId, at, at);
        BigDecimal price = ControlHistoryStore.number(reference.get("reference_price"));
        long time = ((Number) reference.get("reference_time")).longValue();
        if (!ticks.isEmpty() && ((Number) ticks.get(0).get("source_time")).longValue() >= time) {
            price = ControlHistoryStore.number(ticks.get(0).get("price"));
            time = ((Number) ticks.get(0).get("source_time")).longValue();
        }
        store.db.update("UPDATE market_control_hold SET reference_price=?,reference_time=?,offset_price=?,activated_at=?,last_price=?,generated_at=?,source_time=? WHERE task_id=?",
            price, time, displayedPrice.subtract(price), at, displayedPrice, at, time, task.id);
    }
    Map<String,Object> observe(PersistentPriceControl.Task task, Map<String,Object> raw, long now) {
        return store.locked(task.symbolId, () -> {
            Map<String,Object> hold = active(task.id);
            long sourceTime = QuoteState.time(raw.get("sourceTimestamp"));
            if (hold.isEmpty() || !Boolean.TRUE.equals(raw.get("available"))
                    || sourceTime < ((Number) hold.get("source_time")).longValue() || now <= ((Number) hold.get("activated_at")).longValue()) return hold;
            BigDecimal price = ControlHistoryStore.number(raw.get("price"))
                .add(ControlHistoryStore.number(hold.get("offset_price")))
                .max(BigDecimal.ONE.movePointLeft(task.pricePrecision)).setScale(task.pricePrecision, RoundingMode.HALF_UP);
            if (sourceTime == ((Number) hold.get("source_time")).longValue()
                    && price.compareTo(ControlHistoryStore.number(hold.get("last_price"))) == 0) return hold;
            long generated = Math.max(now, ((Number) hold.get("generated_at")).longValue() + 1);
            store.generatedPoints(task.id, task.symbolId, Collections.singletonList(new ControlHistoryStore.PricePoint(generated, price)));
            store.db.update("UPDATE market_control_hold SET last_price=?,generated_at=?,source_time=? WHERE task_id=?",
                price, generated, sourceTime, task.id);
            return active(task.id);
        });
    }
    void release(String task, long now) {
        store.db.update("UPDATE market_control_hold SET released_at=? WHERE task_id=? AND released_at IS NULL", now, task);
    }
}
