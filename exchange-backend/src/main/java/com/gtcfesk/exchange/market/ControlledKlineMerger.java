package com.gtcfesk.exchange.market;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.LongConsumer;

/** Every chart route uses these same persisted minutes. Provider OHLC is never shifted or overwritten. */
@Service
public class ControlledKlineMerger {
    private final ControlHistoryStore store;
    public ControlledKlineMerger(ControlHistoryStore store) { this.store = store; }

    @SuppressWarnings("unchecked")
    public Map<String, Object> merge(long symbol, String interval, int limit, Long cursor,
            Map<String, Object> external, LongConsumer requestMinutes) {
        return merge(symbol, interval, limit, cursor, external, requestMinutes, true);
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> merge(long symbol, String interval, int limit, Long cursor,
            Map<String, Object> external, LongConsumer requestMinutes, boolean utcAnchors) {
        long width = RandomMarketPath.duration(interval);
        long end = cursor == null ? System.currentTimeMillis() : cursor;
        TreeMap<Long, Map<String, Object>> bars = new TreeMap<>();
        List<Map<String, Object>> source = store.db.query("SELECT body FROM market_source_candle WHERE symbol_id=? AND period=? AND candle_at<=? ORDER BY candle_at DESC LIMIT ?",
            (rs, n) -> store.decode(rs.getString(1)), symbol, interval, end, limit);
        for (Map<String, Object> row : source)
            if (ControlHistoryStore.periodCandle(row, interval)) bars.put(ControlHistoryStore.time(row), row);
        for (Map<String, Object> row : ControlHistoryStore.rows(external))
            if (ControlHistoryStore.time(row) <= end && ControlHistoryStore.periodCandle(row, interval))
                bars.put(ControlHistoryStore.time(row), new LinkedHashMap<>(row));
        TreeMap<Long, Map<String, Object>> anchors = new TreeMap<>(bars);
        // A cursor before a session must not re-bucket that future session using UTC.
        for (Map<String, Object> row : store.candles(symbol, interval, end + 1, end + width - 1))
            anchors.put(ControlHistoryStore.time(row), row);
        // Bound rows by the requested number of occupied periods, never by a seven-day window.
        List<Long> recentBuckets = store.db.queryForList("SELECT MIN(minute_at) AS bucket_start FROM market_mixed_minute WHERE symbol_id=? AND minute_at<=? GROUP BY FLOOR(minute_at / ?) ORDER BY bucket_start DESC LIMIT ?",
            Long.class, symbol, end + width - 1, width, limit + 2);
        // A short provider page must not hide older controls that still fit in the requested page.
        long from = recentBuckets.isEmpty() ? 0
            : Math.floorDiv(recentBuckets.get(recentBuckets.size() - 1), width) * width - width;
        List<Map<String, Object>> mixed = store.mixed(symbol, from, end + width - 1);
        boolean noAnchors = width >= 3600000 && !utcAnchors && anchors.isEmpty();
        boolean missingAnchor = noAnchors && !mixed.isEmpty();
        TreeMap<Long, List<Map<String, Object>>> affected = new TreeMap<>();
        for (Map<String, Object> minute : mixed) {
            if (noAnchors) break; // A missing session boundary is not permission to invent one.
            long time = ControlHistoryStore.time(minute);
            Long anchor = anchors.floorKey(time);
            // Prefer the actual provider boundary (including exchange sessions and DST).
            Long nextAnchor = anchor == null ? null : anchors.higherKey(anchor);
            long boundary = anchor == null ? 0 : anchor + width;
            if (width >= 86400000 && nextAnchor != null && Math.abs(nextAnchor - boundary) <= 3600000) boundary = nextAnchor;
            if (width >= 86400000 && !utcAnchors && (anchor == null || time >= boundary)) {
                missingAnchor = true; continue; // Do not extrapolate daily sessions across closures or unknown DST boundaries.
            }
            long bucket = anchor != null && time < boundary ? anchor : fallbackBucket(time, width, anchors);
            if (bucket > end) continue;
            affected.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(minute);
        }
        for (Map.Entry<Long, List<Map<String, Object>>> entry : affected.entrySet()) {
            long start = entry.getKey(), bucketEnd = start + width;
            Long next = anchors.higherKey(start);
            if (width >= 86400000 && next != null && Math.abs(next - bucketEnd) <= 3600000) bucketEnd = next;
            long stop = Math.min(bucketEnd - 1, System.currentTimeMillis());
            TreeMap<Long, Map<String, Object>> minutes = new TreeMap<>();
            for (Map<String, Object> row : store.candles(symbol, "1m", start, stop)) minutes.put(ControlHistoryStore.time(row), row);
            for (Map<String, Object> row : entry.getValue()) minutes.put(ControlHistoryStore.time(row), row);
            if (width > 60000 && requestMinutes != null) {
                // Provider page size is bounded. Each page uses a stable cursor and the existing source queue.
                for (long cursorEnd = bucketEnd - 1; cursorEnd >= start; cursorEnd -= 1000 * 60000L) requestMinutes.accept(cursorEnd);
            }
            Map<String, Object> bar = aggregate(start, minutes.values());
            bar.put("partial", true); // Tick coverage and minute OHLC cannot prove a full second-by-second path.
            bar.put("controlled", true); bar.put("minuteCount", minutes.size());
            Integer publications = store.db.queryForObject("SELECT COUNT(*) FROM market_control_publication p JOIN market_control_task t ON t.id=p.task_id WHERE t.symbol_id=? AND p.from_at<? AND p.to_at>=?",
                Integer.class, symbol, bucketEnd, start);
            if (publications != null && publications > 0) bar.put("historyReplaced", true);
            bars.put(start, bar);
        }
        while (bars.size() > limit) bars.pollFirstEntry();
        Map<String, Object> result = new HashMap<>(external);
        Map<String, Object> data = new HashMap<>((Map<String, Object>) external.get("data"));
        data.put("kline_list", new ArrayList<>(bars.values())); data.put("merged", true);
        if (missingAnchor) data.put("missingData", "source_period_anchor");
        if (!bars.isEmpty()) { result.put("ret", 200); data.put("status", "available"); }
        result.put("data", data); return result;
    }
    static long fallbackBucket(long time, long width, NavigableMap<Long, ?> anchors) {
        long offset = anchors.isEmpty() ? (width == 604800000L ? 4 * 86400000L : 0) : Math.floorMod(anchors.firstKey(), width);
        return Math.floorDiv(time - offset, width) * width + offset;
    }
    static Map<String, Object> aggregate(long time, Collection<Map<String, Object>> rows) {
        Map<String, Object> bar = new LinkedHashMap<>();
        java.math.BigDecimal high = null, low = null, volume = java.math.BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            bar.putIfAbsent("open_price", row.get("open_price")); bar.put("close_price", row.get("close_price"));
            java.math.BigDecimal h = ControlHistoryStore.number(row.get("high_price")), l = ControlHistoryStore.number(row.get("low_price"));
            high = high == null ? h : high.max(h); low = low == null ? l : low.min(l);
            if (row.get("volume") instanceof Number) volume = volume.add(ControlHistoryStore.number(row.get("volume")));
        }
        bar.put("timestamp", time); bar.put("high_price", high); bar.put("low_price", low); bar.put("volume", volume); return bar;
    }
}
