package com.gtcfesk.exchange.market;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen pre-optimization normalizer; no network, database, or wall clock. */
class KlineRetentionRegressionTest {
    private final MarketQuoteSource source = new MarketQuoteSource();
    private final Before before = new Before();
    private Map<String,Object> raw(int count, int seed) {
        Random random = new Random(seed);
        List<Object> times = new ArrayList<>(), opens = new ArrayList<>(), closes = new ArrayList<>();
        List<Object> highs = new ArrayList<>(), lows = new ArrayList<>(), volumes = new ArrayList<>();
        for (int i=0;i<count;i++) {
            times.add(1700000000L + random.nextInt(Math.max(1,count))*60L);
            opens.add(i%17==0 ? null : 100.125+i);
            closes.add(i%23==0 ? null : String.valueOf(101.25+i));
            highs.add(i%19==0 ? null : 102.5+i); lows.add(99.25+i); volumes.add(i%13==0 ? "bad" : i);
        }
        Map<String,Object> quote = new HashMap<>();
        quote.put("open",opens);quote.put("close",closes);quote.put("high",highs);quote.put("low",lows);quote.put("volume",volumes);
        Map<String,Object> item=new HashMap<>();item.put("timestamp",times);
        item.put("indicators",Collections.singletonMap("quote",Collections.singletonList(quote)));
        return Collections.singletonMap("chart",Collections.singletonMap("result",Collections.singletonList(item)));
    }
    private Map<String,Object> actual(Map<String,Object> raw, int limit) {
        return ReflectionTestUtils.invokeMethod(source,"normalizeYahooKlineResponse",raw,"TEST","1m",limit);
    }
    @Test void differentialFieldsOrderingNullsDuplicatesAndLimits() {
        int cases=0;
        for (int seed=0;seed<20;seed++) for(int count:new int[]{0,1,10,100,7200}) for(int limit:new int[]{1,10,100,1000,10000}) {
            Map<String,Object> raw=raw(count,seed);
            assertEquals(before.normalizeYahooKlineResponse(raw,"TEST","1m",limit),actual(raw,limit));cases++;
        }
        // Preserve original malformed-input behavior; fixing it is a separate business change.
        assertEquals(before.normalizeYahooKlineResponse(Collections.emptyMap(),"TEST","1m",10),actual(Collections.emptyMap(),10));
        System.out.println("DIFFERENTIAL normalizer cases="+cases+" plus malformed; exact Map/List equality");
    }
    @Test void cachedTailDoesNotRetainParentList() {
        List<?> rows=ControlHistoryStore.rows(actual(raw(7200,1),10));
        if (Boolean.getBoolean("performance.baseline")) assertNotEquals(ArrayList.class,rows.getClass());
        else assertEquals(ArrayList.class,rows.getClass(),"Cache must not retain ArrayList.SubList.root");
        assertEquals(10,rows.size());
        System.out.println("RETENTION visible="+rows.size()+" implementation="+rows.getClass().getName());
    }
    private static class Before {
    private Map<String, Object> normalizeYahooKlineResponse(Map<String, Object> raw, String code, String interval, int requiredLimit) {
        Map<String, Object> result = new HashMap<>();
        result.put("ret", 200);
        result.put("msg", "ok");

        Map<String, Object> dataOut = new HashMap<>();
        dataOut.put("code", code);
        List<Map<String, Object>> normalizedList = new ArrayList<>();

        try {
            Map<?, ?> chart = (Map<?, ?>) raw.get("chart");
            List<?> resultList = (List<?>) chart.get("result");
            if (resultList != null && !resultList.isEmpty()) {
                Map<?, ?> resultObj = (Map<?, ?>) resultList.get(0);
                List<?> timestamps = (List<?>) resultObj.get("timestamp");
                Map<?, ?> indicators = (Map<?, ?>) resultObj.get("indicators");
                List<?> quoteList = (List<?>) indicators.get("quote");
                
                if (timestamps != null && quoteList != null && !quoteList.isEmpty()) {
                    Map<?, ?> quote = (Map<?, ?>) quoteList.get(0);
                    List<?> opens = (List<?>) quote.get("open");
                    List<?> highs = (List<?>) quote.get("high");
                    List<?> lows = (List<?>) quote.get("low");
                    List<?> closes = (List<?>) quote.get("close");
                    List<?> volumes = (List<?>) quote.get("volume");

                    for (int i = 0; i < timestamps.size(); i++) {
                        if (opens.get(i) == null || closes.get(i) == null) continue; // 过滤空数据

                        Map<String, Object> out = new HashMap<>();
                        // Yahoo 返回的是秒级时间戳
                        long ts = parseLong(timestamps.get(i));
                        out.put("timestamp", ts);
                        out.put("open_price", parseDouble(opens.get(i)));
                        out.put("high_price", parseDouble(highs.get(i)));
                        out.put("low_price", parseDouble(lows.get(i)));
                        out.put("close_price", parseDouble(closes.get(i)));
                        out.put("volume", parseDouble(volumes.get(i)));
                        out.put("turnover", 0.0);
                        normalizedList.add(out);
                    }
                }
            }
        } catch (Exception e) {
            // Malformed items are rejected by the snapshot owner.
        }

        // 按 timestamp 升序排序
        normalizedList.sort(Comparator.comparingLong(m -> ((Number) m.getOrDefault("timestamp", 0L)).longValue()));
        
        // 截取需要的 limit
        if (normalizedList.size() > requiredLimit) {
            normalizedList = normalizedList.subList(normalizedList.size() - requiredLimit, normalizedList.size());
        }
        
        dataOut.put("kline_list", normalizedList);
        result.put("data", dataOut);
        return result;
    }
    private Double parseDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return null;
            return Double.parseDouble(s);
        } catch (Exception ignore) {
            return null;
        }
    }
    private Long parseLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return null;
            return Long.parseLong(s);
        } catch (Exception ignore) {
            return null;
        }
    }
    }
}
