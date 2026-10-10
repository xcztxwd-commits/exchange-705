package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.BigDecimal;
import java.util.*;

/** Bounded open candles in the existing committed runtime, separate from protected closed history. */
final class LiveKline {
    static final String KEY = "liveKlines";
    private static final List<String> PERIODS = Arrays.asList("1m", "5m", "15m", "30m", "1h", "1d", "1w", "1M");

    @SuppressWarnings("unchecked")
    static void capture(ControlHistoryStore store, TradingSymbol config, Map<String,Object> quote,
            Map<String,Object> previous, long at) {
        if (!Boolean.TRUE.equals(quote.get("available")) || !QuoteState.valid(quote)) {
            if (previous.containsKey(KEY)) quote.put(KEY,previous.get(KEY));
            return;
        }
        Map<String,Object> old = previous.get(KEY) instanceof Map ? (Map<String,Object>)previous.get(KEY) : Collections.emptyMap();
        if (!Objects.equals(previous.get("simulationSession"), quote.get("simulationSession"))
                || QuoteState.time(previous.get("historyRestoreRevision")) != store.historyRestoreRevision(config.getId())) old = Collections.emptyMap();
        Map<String,Object> live = new LinkedHashMap<>();
        BigDecimal price = ControlHistoryStore.number(quote.get("price"));
        for (String period : PERIODS) {
            List<Map<String,Object>> retained = old.get(period) instanceof List ? (List<Map<String,Object>>)old.get(period) : Collections.emptyList();
            Map<String,Object> last = retained.isEmpty() ? null : retained.get(retained.size()-1);
            long start = anchor(store, config, period, at);
            if (start < 0) continue; // Missing exchange/session anchors remain unavailable, never invented.
            Map<String,Object> bar = last != null && start == ControlHistoryStore.time(last) ? new LinkedHashMap<>(last) : new LinkedHashMap<>();
            if (bar.isEmpty()) {
                bar.put("timestamp", start); bar.put("open_price", price); bar.put("high_price", price);
                bar.put("low_price", price); bar.put("volume", 0); bar.put("partial", true);
            }
            bar.put("high_price", ControlHistoryStore.number(bar.get("high_price")).max(price));
            bar.put("low_price", ControlHistoryStore.number(bar.get("low_price")).min(price));
            bar.put("close_price", price); bar.put("updatedAt", at);
            List<Map<String,Object>> rows = new ArrayList<>(2);
            if (last != null && start != ControlHistoryStore.time(last)) rows.add(last);
            else if (retained.size() > 1) rows.add(retained.get(0));
            rows.add(bar); live.put(period, rows);
        }
        quote.put(KEY, live); quote.put("liveCapturedAt",at);
    }

    private static long anchor(ControlHistoryStore store, TradingSymbol config, String period, long at) {
        long width = RandomMarketPath.duration(period);
        if (width < 3600000) return Math.floorDiv(at,width)*width;
        boolean simulated=RandomMarketPath.enabled(config);
        // Capture is a writer operation under the runtime fence. Use current reads:
        // a companion transaction may already have an older repeatable-read view.
        List<Map<String,Object>> source = simulated
            ? store.db.query("SELECT body FROM market_simulation_source_candle WHERE tenant_id=? AND symbol_id=? AND session_at=? AND period=? AND candle_at<=? ORDER BY candle_at DESC LIMIT 1 FOR UPDATE",
                (rs,n) -> store.decode(rs.getString(1)), ControlHistoryStore.tenant(), config.getId(), config.getRandomMarketStartedAt(), period, at)
            : store.db.query("SELECT body FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period=? AND candle_at<=? ORDER BY candle_at DESC LIMIT 1 FOR UPDATE",
                (rs,n) -> store.decode(rs.getString(1)), ControlHistoryStore.tenant(), config.getId(), period, at);
        NavigableMap<Long,Object> anchors=new TreeMap<>();
        if (!source.isEmpty() && ControlHistoryStore.periodCandle(source.get(0),period)) {
            long start=ControlHistoryStore.time(source.get(0)); anchors.put(start,source.get(0));
            if(at<RandomMarketPath.periodEnd(period,start)) return start;
        }
        if (simulated || ExchangeQuoteSource.supports(ForexQuoteMarketService.sourceCategory(config)) || "1h".equals(period) && !anchors.isEmpty())
            return "1M".equals(period) ? RandomMarketPath.monthStart(at) : simulated && anchors.isEmpty()
                ? Math.floorDiv(at,width)*width : ControlledKlineMerger.fallbackBucket(at,width,anchors);
        return -1; // Unknown daily/session boundaries remain unavailable.
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> merge(Map<String,Object> result, Map<String,Object> quote, String epoch, String period, int limit) {
        Map<String,Object> data = new LinkedHashMap<>((Map<String,Object>)result.get("data"));
        Map<String,Object> merged = new LinkedHashMap<>(result); merged.put("data",data);
        if (!Boolean.TRUE.equals(quote.get("available")) || !(quote.get(KEY) instanceof Map)
                || !Objects.equals(quote.get("liveQuoteVersion"),quote.get("quoteVersion"))) return merged;
        Object values = ((Map<?,?>)quote.get(KEY)).get(period);
        if (!(values instanceof List) || ((List<?>)values).isEmpty()) return merged;
        List<Map<String,Object>> live = (List<Map<String,Object>>)values;
        Map<String,Object> current = live.get(live.size()-1);
        long at = QuoteState.time(current.get("updatedAt")), start = ControlHistoryStore.time(current);
        if (at < start || at >= RandomMarketPath.periodEnd(period,start)
                || ControlHistoryStore.number(current.get("close_price")).compareTo(ControlHistoryStore.number(quote.get("price"))) != 0) return merged;
        TreeMap<Long,Map<String,Object>> bars = new TreeMap<>();
        for (Map<String,Object> bar : ControlHistoryStore.rows(result)) bars.put(ControlHistoryStore.time(bar),bar);
        // Newly closed live bars fill absent tails only. Existing closed/protected bodies stay exact.
        for (Map<String,Object> bar : live) if (ControlHistoryStore.time(bar) < start) bars.putIfAbsent(ControlHistoryStore.time(bar),bar);
        Map<String,Object> bar = new LinkedHashMap<>(current), base = bars.get(start);
        if (base != null) {
            bar.putAll(base); bar.put("high_price",ControlHistoryStore.number(base.get("high_price")).max(ControlHistoryStore.number(current.get("high_price"))));
            bar.put("low_price",ControlHistoryStore.number(base.get("low_price")).min(ControlHistoryStore.number(current.get("low_price"))));
            bar.put("close_price",current.get("close_price"));
        }
        bars.put(start,bar);
        if (bars.lastKey() != start) return merged;
        while (bars.size() > limit) bars.pollFirstEntry();
        data.put("kline_list",new ArrayList<>(bars.values())); data.put("live",true);
        data.put("epoch",epoch); data.put("quoteVersion",quote.get("quoteVersion")); data.put("updatedAt",at);
        data.put("status","available"); merged.put("ret",200);
        return merged;
    }
}
