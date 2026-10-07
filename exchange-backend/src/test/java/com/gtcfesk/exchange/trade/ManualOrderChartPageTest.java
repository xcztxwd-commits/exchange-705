package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualOrderChartPageTest {
    TradingSymbol symbol() {TradingSymbol s=new TradingSymbol();s.setSymbol("JPY=X");s.setMarketSource("yahoo");return s;}
    Map<String,Object> candle(long time) {Map<String,Object> row=new LinkedHashMap<>();row.put("timestamp",time);row.put("open_price","157.5751234567890123");row.put("low_price","157");row.put("high_price","158");row.put("close_price","157.6");return row;}
    Map<String,Object> feed(List<Map<String,Object>> rows,boolean pending,String status) {Map<String,Object> state=new HashMap<>();state.put("kline_list",rows);state.put("pending",pending);state.put("status",status);return Collections.singletonMap("data",state);}
    long finished() {return Math.floorDiv(System.currentTimeMillis(),60000)*60000;}
    @Test void firstPageReadsOnceAndKeepsOnlyTheNewest200CompletedCandles() {
        long end=finished();ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);
        List<Map<String,Object>> rows=new ArrayList<>();for(int i=220;i>=-1;i--)rows.add(candle(end-i*60000L));
        Map<String,Object> incomplete=candle(end-230*60000L);incomplete.remove("high_price");rows.add(incomplete);
        when(market.historicalKline("JPY=X","1m",200,end-1)).thenReturn(feed(rows,false,"available"));
        Map<String,Object> page=new ManualOrderPrices(market).chart(symbol(),"America/New_York",end-1,200);
        List<Map<String,Object>> candles=(List<Map<String,Object>>)page.get("candles");assertEquals(200,candles.size());
        assertEquals(end-200*60000L,candles.get(0).get("timestamp"));assertEquals(end-60000,candles.get(199).get("timestamp"));
        assertEquals("157.5751234567890123",candles.get(0).get("price"));assertEquals("157",candles.get(0).get("low"));
        assertEquals(end-200*60000L-1,page.get("nextEndTime"));assertEquals(true,page.get("hasMore"));
        verify(market,times(1)).historicalKline("JPY=X","1m",200,end-1);verifyNoMoreInteractions(market);
    }
    @Test void closedMarketGapsDoNotHideExistingOlderDatabaseCandles() {
        long end=finished(),friday=end-3*86400000L;ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);
        when(market.historicalKline("JPY=X","1m",200,end-1)).thenReturn(feed(Arrays.asList(candle(friday-60000),candle(friday)),false,"available"));
        Map<String,Object> page=new ManualOrderPrices(market).chart(symbol(),"UTC",end-1,200);
        assertEquals(2,((List<?>)page.get("candles")).size());assertEquals(friday-60001,page.get("nextEndTime"));assertEquals(true,page.get("hasMore"));
        long cursor=friday-60001;when(market.historicalKline("JPY=X","1m",200,cursor)).thenReturn(feed(Collections.singletonList(candle(friday-120000)),false,"available"));
        Map<String,Object> older=new ManualOrderPrices(market).chart(symbol(),"UTC",cursor,200);
        assertEquals(friday-120001,older.get("nextEndTime"));assertEquals(friday-60000,older.get("to"));
    }
    @Test void pendingAndUnavailablePagesStayExplicitWithoutInventingCandles() {
        long end=finished();ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);
        when(market.historicalKline("JPY=X","1m",200,end-1)).thenReturn(feed(Collections.emptyList(),true,"unavailable"));
        ManualOrderPrices prices=new ManualOrderPrices(market);Map<String,Object> page=prices.chart(symbol(),"UTC",end-1,200);
        assertEquals("loading",page.get("status"));assertEquals(true,page.get("pending"));assertTrue(((List<?>)page.get("candles")).isEmpty());assertEquals(end-200*60000L-1,page.get("nextEndTime"));
        when(market.historicalKline("JPY=X","1m",200,end-1)).thenReturn(feed(Collections.emptyList(),false,"unavailable"));
        assertEquals("unavailable",prices.chart(symbol(),"UTC",end-1,200).get("status"));
        long start=finished()-ManualOrderGenerator.RANGE;
        when(market.historicalKline("JPY=X","1m",200,start+59999)).thenReturn(feed(Collections.singletonList(candle(start)),false,"available"));
        assertEquals(false,prices.chart(symbol(),"UTC",start+59999,200).get("hasMore"));
    }
    @Test void thirtyDayBoundaryAcceptsOlderCandlesAndRejectsEarlierTimes() {
        long end=finished(),from=end-30*86400000L,older=end-20*86400000L;
        assertEquals(30*86400000L,ManualOrderGenerator.RANGE);
        ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);
        when(market.historicalKline("JPY=X","1m",200,older+59999)).thenReturn(feed(Arrays.asList(candle(from-60000),candle(from),candle(older)),false,"available"));
        ManualOrderPrices prices=new ManualOrderPrices(market);Map<String,Object> page=prices.chart(symbol(),"UTC",older+59999,200);
        List<Map<String,Object>> candles=(List<Map<String,Object>>)page.get("candles");
        assertEquals(from,page.get("from"));assertEquals(2,candles.size());assertEquals(from,candles.get(0).get("timestamp"));assertEquals(older,candles.get(1).get("timestamp"));assertEquals(false,page.get("hasMore"));
        assertThrows(BusinessException.class,()->prices.chart(symbol(),"UTC",from-1,200));
        verify(market,times(1)).historicalKline("JPY=X","1m",200,older+59999);verifyNoMoreInteractions(market);
        SimpleManualOrderGenerator.Request request=new SimpleManualOrderGenerator.Request();request.openTime=from;request.closeTime=older;
        assertDoesNotThrow(()->SimpleManualOrderGenerator.validateTimes(request,from,end));
        request.openTime=from-60000;assertThrows(BusinessException.class,()->SimpleManualOrderGenerator.validateTimes(request,from,end));
    }
    @Test void invalidRangesAndPageSizesAreRejectedBeforeAnyMarketRead() {
        ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);ManualOrderPrices prices=new ManualOrderPrices(market);
        for(int limit:new int[]{0,1,201,10080})assertThrows(BusinessException.class,()->prices.chart(symbol(),"UTC",null,limit));
        assertThrows(BusinessException.class,()->prices.chart(symbol(),"UTC",finished()-ManualOrderGenerator.RANGE-60000,200));
        assertThrows(BusinessException.class,()->prices.chart(symbol(),"UTC",System.currentTimeMillis()+86400000L,200));
        assertThrows(BusinessException.class,()->prices.chart(symbol(),"invalid-zone",null,200));verifyNoInteractions(market);
    }
}
