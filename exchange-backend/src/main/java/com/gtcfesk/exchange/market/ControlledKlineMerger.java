package com.gtcfesk.exchange.market;

import static com.gtcfesk.exchange.market.ControlHistoryStore.tenant;

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
        return merge(symbol, interval, limit, cursor, external, requestMinutes, utcAnchors, null);
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> merge(long symbol, String interval, int limit, Long cursor,
            Map<String, Object> external, LongConsumer requestMinutes, boolean utcAnchors,
            java.util.function.BiFunction<Long,Long,List<Map<String,Object>>> baseMinutes) {
        return store.readSnapshot(() -> mergeSnapshot(symbol, interval, limit, cursor, external, utcAnchors, baseMinutes));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> mergeSnapshot(long symbol, String interval, int limit, Long cursor,
            Map<String,Object> external, boolean utcAnchors,
            java.util.function.BiFunction<Long,Long,List<Map<String,Object>>> baseMinutes) {
        Map<String,Object> archived = store.historyOrdering.readExact(symbol, interval, limit, cursor, utcAnchors, external, baseMinutes != null);
        if (archived != null) return archived;
        limit = Math.min(1000, Math.max(1, limit));
        boolean monthly = "1M".equals(interval);
        long width = RandomMarketPath.duration(interval);
        long end = cursor == null ? System.currentTimeMillis() : cursor;
        TreeMap<Long, Map<String, Object>> bars = new TreeMap<>();
        long alignment=width<3600000?width:60000;
        List<Map<String, Object>> source = store.db.query("SELECT body FROM market_source_candle WHERE tenant_id=" + tenant() + " AND symbol_id=? AND period=? AND candle_at<=? AND MOD(candle_at,?)=0 ORDER BY candle_at DESC LIMIT ?",
            (rs, n) -> store.decode(rs.getString(1)), symbol, interval, end, alignment, limit);
        for (Map<String, Object> row : baseMinutes == null ? source : Collections.<Map<String,Object>>emptyList())
            if (ControlHistoryStore.periodCandle(row, interval)) bars.put(ControlHistoryStore.time(row), row);
        for (Map<String, Object> row : ControlHistoryStore.rows(external))
            if (ControlHistoryStore.time(row) <= end && ControlHistoryStore.periodCandle(row, interval))
                bars.put(ControlHistoryStore.time(row), new LinkedHashMap<>(row));
        TreeMap<Long, Map<String, Object>> frozen = new TreeMap<>(bars);
        long session = baseMinutes == null ? 0 : QuoteState.time(((Map<String,Object>)external.get("data")).get("simulationSession"));
        if (baseMinutes != null && ((Map<String,Object>)external.get("data")).containsKey("simulationSession")) {
            long offset = frozen.isEmpty() ? 0 : Math.floorMod(frozen.firstKey(), width);
            long latest = monthly ? RandomMarketPath.monthStart(end) : Math.floorDiv(end-offset,width)*width+offset;
            long first = monthly ? java.time.Instant.ofEpochMilli(latest).atZone(java.time.ZoneOffset.UTC)
                    .minusMonths(limit-1L).toInstant().toEpochMilli() : latest-(limit-1L)*width;
            long stop = Math.min(RandomMarketPath.periodEnd(interval,latest)-1,System.currentTimeMillis());
            TreeMap<Long,MinuteAggregate> grouped = new TreeMap<>();
            sourceMinutePages(first,stop,baseMinutes,page -> {
                for (Map<String,Object> row : page) {
                    long time=ControlHistoryStore.time(row); Long anchor=frozen.floorKey(time);
                    long bucket=anchor!=null && time<RandomMarketPath.periodEnd(interval,anchor) ? anchor
                            : monthly ? RandomMarketPath.monthStart(time) : Math.floorDiv(time-offset,width)*width+offset;
                    if(bucket>end) continue;
                    if(width==60000) bars.put(bucket,frozenPrefix(new LinkedHashMap<>(row),frozen.get(bucket),session,time+59999));
                    else grouped.computeIfAbsent(bucket,ignored->new MinuteAggregate()).add(row);
                }
            });
            for (Map.Entry<Long,MinuteAggregate> entry : grouped.entrySet())
                bars.put(entry.getKey(),frozenPrefix(entry.getValue().finish(entry.getKey()),frozen.get(entry.getKey()),session,entry.getValue().lastMinute+59999));
        }
        TreeMap<Long, Map<String, Object>> anchors = new TreeMap<>(bars);
        // A cursor before a session must not re-bucket that future session using UTC.
        for (Map<String, Object> row : store.candles(symbol, interval, end + 1, end + width - 1))
            anchors.put(ControlHistoryStore.time(row), row);
        // Bound rows by the requested number of occupied periods, never by a seven-day window.
        List<Long> recentBuckets = monthly ? Collections.emptyList() : recentMixedBuckets(symbol, end + width - 1, width, limit + 2);
        // A short provider page must not hide older controls that still fit in the requested page.
        long from = monthly ? RandomMarketPath.monthStart(end - (limit + 2L) * 32 * 86400000L) : recentBuckets.isEmpty() ? end + width
            : Math.floorDiv(recentBuckets.get(recentBuckets.size() - 1), width) * width - width;

        boolean noAnchors = width >= 3600000 && !utcAnchors && anchors.isEmpty();
        boolean[] missingAnchor = {false};
        TreeMap<Long, Long> affected = new TreeMap<>();
        store.visibleMixedPages(symbol, from, end + width - 1, page -> {
        for (Map<String, Object> minute : page) {
            if (noAnchors) { missingAnchor[0] = true; break; } // A missing session boundary is not permission to invent one.
            long time = ControlHistoryStore.time(minute);
            Long anchor = anchors.floorKey(time);
            // Prefer the actual provider boundary (including exchange sessions and DST).
            Long nextAnchor = anchor == null ? null : anchors.higherKey(anchor);
            long boundary = anchor == null ? 0 : monthly ? nextAnchor == null ? RandomMarketPath.monthEnd(anchor) : nextAnchor : anchor + width;
            if (!monthly && width >= 86400000 && nextAnchor != null && Math.abs(nextAnchor - boundary) <= 3600000) boundary = nextAnchor;
            if (width >= 86400000 && !utcAnchors && (anchor == null || time >= boundary)) {
                missingAnchor[0] = true; continue; // Do not extrapolate daily sessions across closures or unknown DST boundaries.
            }
            long bucket = anchor != null && time < boundary ? anchor : monthly ? RandomMarketPath.monthStart(time) : fallbackBucket(time, width, anchors);
            if (bucket > end) continue;
            affected.putIfAbsent(bucket, 0L);
        }
        });
        for (Long start : new ArrayList<>(affected.keySet())) {
            long bucketEnd = monthly ? RandomMarketPath.monthEnd(start) : start + width;
            Long next = anchors.higherKey(start);
            if (monthly && next != null) bucketEnd = next;
            else if (width >= 86400000 && next != null && Math.abs(next - bucketEnd) <= 3600000) bucketEnd = next;
            affected.put(start, bucketEnd);
        }
        Set<Long> published = store.publishedBuckets(symbol, affected);
        TreeMap<Long, MinuteAggregate> aggregates = new TreeMap<>();
        long now = System.currentTimeMillis();
        Map<Long,Long> stops = new TreeMap<>();
        for (Map.Entry<Long,Long> entry : affected.entrySet()) {
            long stop = Math.min(entry.getValue() - 1, now);
            if (stop >= entry.getKey()) stops.put(entry.getKey(),stop);
        }
        mergedMinuteWindows(symbol, stops, baseMinutes, page -> {
            for (Map<String,Object> minute : page) {
                long at = ControlHistoryStore.time(minute);
                Map.Entry<Long,Long> bucket = affected.floorEntry(at);
                if (bucket != null && at < bucket.getValue())
                    aggregates.computeIfAbsent(bucket.getKey(), ignored -> new MinuteAggregate()).add(minute);
            }
        });
        for (Long start : affected.keySet()) {
            MinuteAggregate values = aggregates.getOrDefault(start, new MinuteAggregate());
            Map<String,Object> bar = values.finish(start);
            if(baseMinutes!=null) bar=frozenPrefix(bar,frozen.get(start),session,values.lastMinute+59999);
            if(width==60000 && values.overrides==1) bar=new LinkedHashMap<>(values.last);
            else { bar.put("partial", true); bar.put("controlled", true); }
            if(values.restored>0) bar.put("historySourceRestored",true);
            bar.put("minuteCount", values.count);
            if (published.contains(start)) bar.put("historyReplaced", true);
            bars.put(start, bar);
        }
        // requestMinutes is retained for source compatibility only. Reads never enqueue backfill or repair state.
        while (bars.size() > limit) bars.pollFirstEntry();
        Map<String, Object> result = new HashMap<>(external);
        Map<String, Object> data = new HashMap<>((Map<String, Object>) external.get("data"));
        data.put("kline_list", new ArrayList<>(bars.values())); data.put("merged", true);
        data.put("historyRestoreRevision",store.historyRestoreRevision(symbol));
        if (missingAnchor[0]) data.put("missingData", "source_period_anchor");
        List<Integer> missingSource = store.db.queryForList("SELECT 1 FROM market_control_sample s JOIN market_control_task t ON t.tenant_id=s.tenant_id AND t.id=s.task_id JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id LEFT JOIN market_control_publication p ON p.tenant_id=t.tenant_id AND p.task_id=t.id WHERE t.tenant_id=" + tenant() + " AND t.symbol_id=? AND f.state='SOURCE' AND s.generated_at>=? AND s.generated_at<=? AND (p.task_id IS NULL OR s.generated_at>p.to_at) AND NOT EXISTS (SELECT 1 FROM market_source_candle c WHERE c.tenant_id=" + tenant() + " AND c.symbol_id=t.symbol_id AND c.period='1m' AND c.candle_at=FLOOR(s.generated_at/60000)*60000) LIMIT 1", Integer.class, symbol, from, end + width - 1);
        if (!missingSource.isEmpty() && baseMinutes == null) data.put("missingData", "original_source_candles");
        if (!bars.isEmpty()) { result.put("ret", 200); data.put("status", "available"); }
        result.put("data", data); return result;
    }
    /** Indexed descending seek: discover occupied buckets without grouping unrelated history. */
    List<Long> recentMixedBuckets(long symbol, long to, long width, int count) {
        List<Long> buckets = new ArrayList<>();
        long before = to;
        while (buckets.size() < count) {
            List<Long> page = store.db.queryForList("SELECT minute_at FROM market_mixed_minute WHERE tenant_id=? AND symbol_id=? AND minute_at<=? ORDER BY minute_at DESC LIMIT 500",
                Long.class, tenant(), symbol, before);
            if (page.isEmpty()) break;
            for (Long at : page) {
                long bucket = Math.floorDiv(at, width) * width;
                if (buckets.isEmpty() || buckets.get(buckets.size() - 1) != bucket) buckets.add(bucket);
                if (buckets.size() == count) break;
            }
            long lastBucket = Math.floorDiv(page.get(page.size() - 1), width) * width;
            if (lastBucket == Long.MIN_VALUE) break;
            before = lastBucket - 1; // Skip the remaining interior of a bucket already discovered.
        }
        return buckets;
    }
    /** Batch sparse windows together without querying the unrequested gaps between them. */
    private void mergedMinuteWindows(long symbol, Map<Long,Long> windows,
            java.util.function.BiFunction<Long,Long,List<Map<String,Object>>> baseMinutes,
            java.util.function.Consumer<List<Map<String,Object>>> consume) {
        if (baseMinutes != null) {
            // The existing random-session callback has no key directory. Bound every supplied time slice.
            for (Map.Entry<Long,Long> window : windows.entrySet())
                suppliedMinutePages(symbol,window.getKey(),window.getValue(),baseMinutes,consume);
            return;
        }
        Iterator<Map.Entry<Long,Long>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            List<Map.Entry<Long,Long>> pageWindows = new ArrayList<>();
            while (iterator.hasNext() && pageWindows.size() < 500) pageWindows.add(iterator.next());
            StringJoiner candleBounds = new StringJoiner(" OR ","(",")"), mixedBounds = new StringJoiner(" OR ","(",")");
            List<Object> rangeArgs = new ArrayList<>();
            for (Map.Entry<Long,Long> window : pageWindows) {
                candleBounds.add("(candle_at>=? AND candle_at<=?)"); mixedBounds.add("(minute_at>=? AND minute_at<=?)");
                rangeArgs.add(window.getKey()); rangeArgs.add(window.getValue());
            }
            long after = pageWindows.get(0).getKey() - 1;
            while (true) {
                List<Object> arguments = new ArrayList<>(Arrays.asList(tenant(),symbol,after)); arguments.addAll(rangeArgs);
                Collections.addAll(arguments,tenant(),symbol,after); arguments.addAll(rangeArgs);
                List<Long> keys = store.db.queryForList("SELECT minute_at FROM ((SELECT candle_at AS minute_at FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period='1m' AND candle_at>? AND " + candleBounds + " ORDER BY candle_at LIMIT 500) UNION (SELECT minute_at FROM market_mixed_minute WHERE tenant_id=? AND symbol_id=? AND minute_at>? AND " + mixedBounds + " ORDER BY minute_at LIMIT 500)) minute_keys ORDER BY minute_at LIMIT 500",
                    Long.class,arguments.toArray());
                if (keys.isEmpty()) break;
                String placeholders = String.join(",",Collections.nCopies(keys.size(),"?"));
                List<Object> candleArgs = new ArrayList<>(Arrays.asList(tenant(),symbol)); candleArgs.addAll(keys);
                TreeMap<Long,Map<String,Object>> minutes = new TreeMap<>();
                for (Map<String,Object> row : store.db.query("SELECT body FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period='1m' AND candle_at IN (" + placeholders + ") ORDER BY candle_at LIMIT 500",
                        (rs,n) -> store.decode(rs.getString(1)),candleArgs.toArray()))
                    if (ControlHistoryStore.periodCandle(row,"1m")) minutes.put(ControlHistoryStore.time(row),row);
                for (Map<String,Object> row : store.visibleMixedAt(symbol,keys)) minutes.put(ControlHistoryStore.time(row),row);
                for(Map.Entry<Long,Map<String,Object>> row:store.historyOverrides(symbol,keys.get(0),keys.get(keys.size()-1)).entrySet())
                    if(row.getValue()!=null) minutes.put(row.getKey(),new LinkedHashMap<>(row.getValue()));
                consume.accept(new ArrayList<>(minutes.values()));
                if (keys.size() < 500) break;
                after = keys.get(keys.size() - 1);
            }
        }
    }
    /** The existing random-session supplier receives at most 500 minute slots per slice. */
    private static void sourceMinutePages(long from,long to,
            java.util.function.BiFunction<Long,Long,List<Map<String,Object>>> baseMinutes,
            java.util.function.Consumer<List<Map<String,Object>>> consume) {
        for(long first=from;first<=to;) {
            long last=Math.min(to,first+500*60000L-1);
            TreeMap<Long,Map<String,Object>> minutes=new TreeMap<>();
            for(Map<String,Object> row:baseMinutes.apply(first,last))
                if(ControlHistoryStore.periodCandle(row,"1m") && ControlHistoryStore.time(row)>=first && ControlHistoryStore.time(row)<=last)
                    minutes.put(ControlHistoryStore.time(row),row);
            consume.accept(new ArrayList<>(minutes.values()));
            if(last==Long.MAX_VALUE) break;
            first=last+1;
        }
    }
    private void suppliedMinutePages(long symbol, long from, long to,
            java.util.function.BiFunction<Long,Long,List<Map<String,Object>>> baseMinutes,
            java.util.function.Consumer<List<Map<String,Object>>> consume) {
        long first = from;
        while (first <= to) {
            long last = Math.min(to, first + 500 * 60000L - 1);
            TreeMap<Long,Map<String,Object>> minutes = new TreeMap<>();
            for (Map<String,Object> row : baseMinutes.apply(first,last)) minutes.put(ControlHistoryStore.time(row),row);
            for (Map<String,Object> row : store.visibleMixed(symbol, first, last)) minutes.put(ControlHistoryStore.time(row),row);
            consume.accept(new ArrayList<>(minutes.values()));
            if (last == Long.MAX_VALUE) break;
            first = last + 1;
        }
    }
    private static final class MinuteAggregate {
        Object open, close;
        java.math.BigDecimal high, low, volume = java.math.BigDecimal.ZERO;
        int count,restored,overrides; long lastMinute; Map<String,Object> last;
        void add(Map<String,Object> row) {
            last=row; if(Boolean.TRUE.equals(row.get("historySourceRestored"))) restored++;
            if(Boolean.TRUE.equals(row.get("historyRestoreOverride"))) overrides++;
            if (open == null) open = row.get("open_price");
            close = row.get("close_price");
            java.math.BigDecimal h = ControlHistoryStore.number(row.get("high_price")), l = ControlHistoryStore.number(row.get("low_price"));
            high = high == null ? h : high.max(h); low = low == null ? l : low.min(l);
            if (row.get("volume") instanceof Number) volume = volume.add(ControlHistoryStore.number(row.get("volume")));
            lastMinute=ControlHistoryStore.time(row); count++;
        }
        Map<String,Object> finish(long start) {
            Map<String,Object> bar = new LinkedHashMap<>();
            if (count > 0) { bar.put("open_price",open); bar.put("close_price",close); }
            bar.put("timestamp",start); bar.put("high_price",high); bar.put("low_price",low); bar.put("volume",volume);
            return bar;
        }
    }
    private static Map<String,Object> frozenPrefix(Map<String,Object> bar,Map<String,Object> previous,long session,long last) {
        if(previous==null || session==0 || ControlHistoryStore.time(previous)>=session) return bar;
        if(last<session) return new LinkedHashMap<>(previous);
        bar.put("open_price",previous.get("open_price"));
        bar.put("high_price",ControlHistoryStore.number(previous.get("high_price")).max(ControlHistoryStore.number(bar.get("high_price"))));
        bar.put("low_price",ControlHistoryStore.number(previous.get("low_price")).min(ControlHistoryStore.number(bar.get("low_price"))));
        bar.put("volume",previous.getOrDefault("volume",0)); return bar;
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
