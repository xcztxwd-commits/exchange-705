package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualOrderPricesLoadingTest {
    private static final long OPEN=1700000040000L, CLOSE=OPEN+16*3600000L, WINDOW=720*60000L;
    private static long end(long minute) { return (Math.floorDiv(minute,WINDOW)+1)*WINDOW-1; }
    private static Map<String,Object> pending() {
        Map<String,Object> data=new HashMap<>();data.put("pending",true);data.put("code","JPYUSD=X");
        data.put("kline_list",Collections.emptyList());return Collections.singletonMap("data",data);
    }
    private static Map<String,Object> candles(String opening,String closing) {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<2;i++) {Map<String,Object> row=new HashMap<>();row.put("timestamp",i==0?OPEN:CLOSE);row.put("open",i==0?opening:closing);rows.add(row);}
        return Collections.singletonMap("data",Collections.singletonMap("kline_list",rows));
    }
    private static TradingSymbol symbol() {
        TradingSymbol s=new TradingSymbol();s.setSymbol("JPY=X");s.setSourceCategory("Forex");
        s.setMarketSource("yahoo");s.setBaseCurrency("USD");s.setQuoteCurrency("JPY");return s;
    }
    @Test void queuesBothExactConversionWindowsBeforeCheckingPrimaryReadiness() {
        ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);
        when(market.historicalKline(eq("JPY=X"),eq("1m"),eq(720),anyLong())).thenReturn(pending());
        when(market.getKline(eq("JPYUSD=X"),eq("1m"),eq(720),eq("Forex"),anyLong())).thenReturn(pending());
        ManualOrderPrices prices=new ManualOrderPrices(market);
        BusinessException loading=assertThrows(BusinessException.class,()->prices.quote(symbol(),OPEN,CLOSE));
        assertEquals(ManualOrderPrices.HISTORY_LOADING,loading.getCode());
        verify(market).historicalKline("JPY=X","1m",720,end(OPEN));
        verify(market).historicalKline("JPY=X","1m",720,end(CLOSE));
        verify(market).getKline("JPYUSD=X","1m",720,"Forex",end(OPEN));
        verify(market).getKline("JPYUSD=X","1m",720,"Forex",end(CLOSE));
        // Once the queued windows arrive, no extra two-minute conversion request is needed.
        when(market.historicalKline(eq("JPY=X"),eq("1m"),eq(720),anyLong())).thenReturn(candles("157.50599670410156","157.41299438476562"));
        when(market.getKline(eq("JPYUSD=X"),eq("1m"),eq(720),eq("Forex"),anyLong())).thenReturn(candles("0.006349","0.006352"));
        Map<String,Object> quote=prices.quote(symbol(),OPEN,CLOSE);
        assertEquals(new BigDecimal("0.006349"),quote.get("openRate"));
        assertEquals(new BigDecimal("0.006352"),quote.get("closeRate"));
        assertEquals(BigDecimal.ONE,quote.get("marginRate"));
        verify(market,never()).getKline(anyString(),anyString(),eq(2),anyString(),anyLong());
    }
    @Test void missingMinuteDoesNotRetryOrBorrowItsNeighbour() {
        Map<String,Object> ready=candles("1","2");
        BusinessException missing=assertThrows(BusinessException.class,()->ManualOrderPrices.exact(ready,OPEN+60000));
        assertEquals(400,missing.getCode());assertTrue(missing.getMessage().contains("不使用邻近价格"));
        BusinessException loading=assertThrows(BusinessException.class,()->ManualOrderPrices.exact(pending(),OPEN));
        assertEquals(425,loading.getCode());
    }
}
