package com.gtcfesk.exchange.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.market.MarketHttp;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class YahooHistoryCalendarTest {
    @Test void actualMinutesOnlyNoCalendarFromNullPrices() throws Exception {
        long t=Instant.parse("2026-09-21T16:00:00Z").toEpochMilli();
        String raw="{\"timestamp\":["+(t/1000)+","+(t/1000+60)+","+(t/1000+120)+","+(t/1000+180)+"],\"indicators\":{\"quote\":[{\"open\":[150.25,null,0,151]}]}}";
        List<Map<String,Object>> rows=YahooHistoryCalendar.parse(new ObjectMapper().readTree(raw),t,t+180000,t+180000,ZoneId.of("Asia/Shanghai"));
        assertEquals(1,rows.size());assertEquals("2026-09-22T00:00",rows.get(0).get("local"));assertEquals("150.25",rows.get(0).get("price"));
        assertTrue(YahooHistoryCalendar.parse(new ObjectMapper().readTree("{}"),t,t+180000,t,ZoneId.of("UTC")).isEmpty());
    }
    @Test void cryptoNeverQueriesYahooCalendar() {
        MarketHttp http=mock(MarketHttp.class);TradingSymbol symbol=new TradingSymbol();symbol.setMarketSource("binance");
        YahooHistoryCalendar calendar=new YahooHistoryCalendar(http,new ObjectMapper());
        assertThrows(BusinessException.class,()->calendar.page(symbol,"2026-09","UTC",0));verifyNoInteractions(http);
    }
    @Test void futureAndOutOfMonthPagesDoNotFetch() {
        MarketHttp http=mock(MarketHttp.class);TradingSymbol symbol=new TradingSymbol();symbol.setMarketSource("yahoo");
        YahooHistoryCalendar calendar=new YahooHistoryCalendar(http,new ObjectMapper());
        assertEquals(Collections.emptyList(),calendar.page(symbol,"2099-01","UTC",0).get("minutes"));
        assertEquals(Collections.emptyList(),calendar.page(symbol,"2025-02","UTC",6).get("minutes"));
        assertThrows(BusinessException.class,()->calendar.page(symbol,"2025-02","UTC",7));verifyNoInteractions(http);
    }
}
