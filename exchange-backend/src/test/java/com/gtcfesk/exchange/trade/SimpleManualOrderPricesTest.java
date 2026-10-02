package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimpleManualOrderPricesTest {
    private static final long OPEN=1700000040000L,CLOSE=OPEN+60000;
    private static Map<String,Object> data(boolean pending,String price) {
        Map<String,Object> out=new HashMap<>();out.put("pending",pending);List<Map<String,Object>> rows=new ArrayList<>();
        if(!pending)for(long time:new long[]{OPEN,CLOSE}) {Map<String,Object> row=new HashMap<>();row.put("timestamp",time/1000);row.put("open_price",price);row.put("close_price",price);row.put("low_price",price);row.put("high_price",price);rows.add(row);}
        out.put("kline_list",rows);return Collections.singletonMap("data",out);
    }
    private static TradingSymbol symbol() {
        TradingSymbol s=new TradingSymbol();s.setSymbol("JPY=X");s.setSourceCategory("Forex");s.setMarketSource("yahoo");s.setBaseCurrency("USD");s.setQuoteCurrency("JPY");return s;
    }
    @Test void chartExposesOnlyCompleteSevenDayCandlesAndKeepsPrecisionAsStrings() {
        ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);long end=Math.floorDiv(System.currentTimeMillis(),60000)*60000;
        List<Map<String,Object>> rows=new ArrayList<>();
        for(long time:new long[]{end-120000,end-60000,end,end+60000,end-ManualOrderGenerator.RANGE-60000}) {
            Map<String,Object> row=new HashMap<>();row.put("timestamp",time/1000);row.put("open_price","100.1234567890123456");row.put("low_price","100.0000000000000001");row.put("high_price","101.0000000000000001");row.put("close_price","100.3456789012345678");rows.add(row);
        }
        Map<String,Object> bad=new HashMap<>(rows.get(0));bad.put("timestamp",(end-180000)/1000);bad.remove("high_price");rows.add(bad);
        when(market.historicalKline(anyString(),anyString(),anyInt(),anyLong())).thenReturn(Collections.singletonMap("data",Collections.singletonMap("kline_list",rows)));
        Map<String,Object> result=new ManualOrderPrices(market).chart(symbol(),"America/New_York");
        List<Map<String,Object>> candles=(List<Map<String,Object>>)result.get("candles");assertEquals(2,candles.size());assertEquals(end-120000,candles.get(0).get("timestamp"));
        assertEquals("100.1234567890123456",candles.get(0).get("price"));assertEquals("100.0000000000000001",candles.get(0).get("low"));assertEquals("101.0000000000000001",candles.get(0).get("high"));assertEquals("100.3456789012345678",candles.get(0).get("close"));
        assertEquals("available",result.get("status"));assertEquals(false,result.get("pending"));assertEquals("1m",result.get("interval"));assertEquals(end-ManualOrderGenerator.RANGE,result.get("from"));
        verify(market,never()).getKline(anyString(),anyString(),anyInt(),anyString(),anyLong());
    }
    @Test void chartLoadingAndUnavailableAreNotFabricatedMarketClosures() {
        ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);when(market.historicalKline(anyString(),anyString(),anyInt(),anyLong())).thenReturn(data(true,"100"));
        ManualOrderPrices prices=new ManualOrderPrices(market);assertEquals("loading",prices.chart(symbol(),"UTC").get("status"));
        when(market.historicalKline(anyString(),anyString(),anyInt(),anyLong())).thenReturn(Collections.singletonMap("data",Collections.singletonMap("status","unavailable")));
        assertEquals("unavailable",prices.chart(symbol(),"UTC").get("status"));assertThrows(BusinessException.class,()->prices.chart(symbol(),"invalid-zone"));
    }
    @Test void pendingConversionPreventsPrematureSearchAndQueuesAllWindows() {
        ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);
        when(market.historicalKline(anyString(),anyString(),anyInt(),anyLong())).thenReturn(data(false,"157.5"));
        when(market.getKline(anyString(),anyString(),anyInt(),anyString(),anyLong())).thenReturn(data(true,"0.0064"));
        ManualOrderPrices prices=new ManualOrderPrices(market);
        assertEquals(425,assertThrows(BusinessException.class,()->prices.simpleCandles(symbol(),OPEN,CLOSE+60000)).getCode());
        verify(market).getKline(eq("JPYUSD=X"),eq("1m"),eq(720),eq("Forex"),anyLong());
        when(market.getKline(anyString(),anyString(),anyInt(),anyString(),anyLong())).thenReturn(data(false,"0.0064"));
        assertEquals(2,prices.simpleCandles(symbol(),OPEN,CLOSE+60000).size());
    }
    @Test void ohlcPriceBasisKeepsUsdBaseMarginRateAndRejectsOutOfRangePrice() {
        ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);
        Map<String,Object> primary=data(false,"157.5");
        for(Map<String,Object> row:(List<Map<String,Object>>)((Map<?,?>)primary.get("data")).get("kline_list")) {row.put("low_price","157");row.put("high_price","158");}
        when(market.historicalKline(anyString(),anyString(),anyInt(),anyLong())).thenReturn(primary);
        when(market.getKline(anyString(),anyString(),anyInt(),anyString(),anyLong())).thenReturn(data(false,"0.0064"));
        ManualOrderPrices prices=new ManualOrderPrices(market);
        Map<String,Object> quote=prices.rangeQuote(symbol(),OPEN,CLOSE,new BigDecimal("157.4"),new BigDecimal("157.8"));
        assertEquals(new BigDecimal("157.4"),quote.get("openPrice"));assertEquals(new BigDecimal("157.8"),quote.get("closePrice"));assertEquals(BigDecimal.ONE,quote.get("marginRate"));assertEquals(new BigDecimal("0.0064"),quote.get("closeRate"));
        assertThrows(BusinessException.class,()->prices.rangeQuote(symbol(),OPEN,CLOSE,new BigDecimal("156.9"),new BigDecimal("157.8")));
        assertEquals(new BigDecimal("157.5"),prices.quote(symbol(),OPEN,CLOSE).get("openPrice"));
    }
}
