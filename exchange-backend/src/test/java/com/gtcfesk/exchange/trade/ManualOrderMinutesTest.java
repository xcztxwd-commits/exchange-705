package com.gtcfesk.exchange.trade;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManualOrderMinutesTest {
    private Map<String,Object> candle(long time,String price) {
        Map<String,Object> row=new HashMap<>();row.put("timestamp",time);row.put("open_price",price);return row;
    }
    @SafeVarargs private final Map<String,Object> response(Map<String,Object>... rows) {
        return Collections.singletonMap("data",Collections.singletonMap("kline_list",Arrays.asList(rows)));
    }
    @Test void onlyActualPositiveMinutesInSelectedDayAndNeverFuture() {
        long from=Instant.parse("2026-09-21T16:00:00Z").toEpochMilli();
        Map<String,Object> data=response(candle(from/1000,"150.1200"),candle(from+60000,"0"),
            candle(from+120000,"null"),candle(from-60000,"1"),candle(from+86400000,"2"),candle(from+1,"3"));
        SortedMap<Long,Map<String,Object>> selected=ManualOrderPrices.selectMinutes(data,from,from+86400000,from+180000,ZoneId.of("Asia/Shanghai"));
        assertEquals(1,selected.size());assertEquals("2026-09-22T00:00",selected.get(from).get("local"));
        assertEquals("150.1200",selected.get(from).get("price"));assertEquals("+08:00",selected.get(from).get("offset"));
        assertTrue(ManualOrderPrices.selectMinutes(data,from,from+86400000,from-1,ZoneId.of("UTC")).isEmpty());
    }
    @Test void repeatedDstMinutesRemainDistinctAndRoundTrip() {
        long first=Instant.parse("2025-11-02T05:30:00Z").toEpochMilli(),second=first+3600000;
        SortedMap<Long,Map<String,Object>> selected=ManualOrderPrices.selectMinutes(response(candle(first,"1"),candle(second,"2")),first,second+60000,second,ZoneId.of("America/New_York"));
        assertEquals(2,selected.size());assertEquals(selected.get(first).get("local"),selected.get(second).get("local"));
        assertEquals("-04:00",selected.get(first).get("offset"));assertEquals("-05:00",selected.get(second).get("offset"));
        selected.forEach((time,row)->assertEquals(time.longValue(),ManualOrderCalculation.minute(row.get("local").toString(),"America/New_York",row.get("offset").toString())));
    }
    @Test void noSyntheticMinutesForEmptyMarketDay() {
        assertTrue(ManualOrderPrices.selectMinutes(response(),0,86400000,86400000,ZoneId.of("UTC")).isEmpty());
    }
    @Test void generationAndPreviewUseSameLedgerPricePrecision() {
        long minute=1700000040000L;
        Map<String,Object> data=response(candle(minute,"0.006345179164260625839"));
        SortedMap<Long,Map<String,Object>> selected=ManualOrderPrices.selectMinutes(data,minute,minute+60000,minute+60000,ZoneOffset.UTC);
        assertEquals(1,selected.size(),"A price accepted by authoritative preview must not disappear from generation");
        assertEquals(ManualOrderPrices.exact(data,minute).toPlainString(),selected.get(minute).get("price"));
    }
}
