package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractConversionTest extends TenantMarketTestContext {
    static Map<String,Object> quote(double price,long age) {
        Map<String,Object> q=new HashMap<>();q.put("price",price);q.put("timestamp",System.currentTimeMillis()-age);q.put("fetchedAt",System.currentTimeMillis());q.put("sourceAvailable",true);return q;
    }
    @Test void freshInverseQuoteReplacesHoursOldRateWithoutUsingFiatCache() {
        ForexQuoteMarketService market=spy(new ForexQuoteMarketService());
        RedisMarketService redis=mock(RedisMarketService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"redis",redis);
        try {
            doReturn(quote(160,0)).when(market).getPrice("JPY=X","Forex");
            doReturn(quote(.0063,3600000)).when(market).getPrice("JPYUSD=X","Forex");
            assertEquals(0,new BigDecimal("0.00625").compareTo(market.requireContractConversionRate("JPY","yahoo")));
            doReturn(quote(200,0)).when(market).getPrice("JPY=X","Forex");
            assertEquals(0,new BigDecimal("0.005").compareTo(market.requireContractConversionRate("JPY","yahoo")));
            verifyNoInteractions(redis);
            doReturn(quote(200,61000)).when(market).getPrice("JPY=X","Forex");
            assertThrows(BusinessException.class,()->market.requireContractConversionRate("JPY","yahoo"));
        } finally {market.stop();}
    }
    @Test void directRatesMinorUnitsMissingAndUnhealthySources() {
        ForexQuoteMarketService market=spy(new ForexQuoteMarketService());
        try {
            Map<String,Object> gbp=quote(1.25,0);doReturn(gbp).when(market).getPrice("GBPUSD=X","Forex");
            assertEquals(0,new BigDecimal("0.0125").compareTo(market.requireContractConversionRate("GBp","yahoo")));
            long expiry=((Number)market.contractConversion("GBP","yahoo").get("conversionExpiresAt")).longValue();
            assertTrue(expiry<=System.currentTimeMillis()+60000);
            gbp.put("sourceAvailable",false);
            assertThrows(BusinessException.class,()->market.requireContractConversionRate("GBP","yahoo"));
            assertThrows(BusinessException.class,()->market.requireContractConversionRate("JPY","yahoo"));
            doReturn(quote(80000,0)).when(market).getPrice("BTCUSDT","Crypto");
            assertEquals(0,new BigDecimal("80000").compareTo(market.requireContractConversionRate("BTC","binance")));
            assertEquals(BigDecimal.ONE,market.requireContractConversionRate("USD","yahoo"));
            assertEquals(BigDecimal.ONE,market.requireContractConversionRate("USDT","binance"));
        } finally {market.stop();}
    }
}
