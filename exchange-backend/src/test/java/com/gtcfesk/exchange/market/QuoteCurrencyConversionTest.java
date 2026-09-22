package com.gtcfesk.exchange.market;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuoteCurrencyConversionTest {
    @Test void configuredCurrencyDefaultsValidationAndPrewarming() {
        java.util.List<String> defaults=com.gtcfesk.exchange.admin.SystemConfigService.conversionCurrencies(null);
        assertEquals(10,defaults.size());assertTrue(defaults.containsAll(java.util.Arrays.asList("CNY","SGD","CHF","HKD","USD")));
        assertEquals(java.util.Arrays.asList("USD","EUR"),com.gtcfesk.exchange.admin.SystemConfigService.conversionCurrencies("eur,EUR"));
        for(String invalid:new String[]{"", "BTC", "USDT", "../", "EUR,", "XXX"})
            assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->com.gtcfesk.exchange.admin.SystemConfigService.conversionCurrencies(invalid));
        ForexQuoteMarketService market=new ForexQuoteMarketService();
        com.gtcfesk.exchange.admin.SystemConfigService configs=org.mockito.Mockito.mock(com.gtcfesk.exchange.admin.SystemConfigService.class);
        com.gtcfesk.exchange.repository.TradingSymbolRepository symbols=org.mockito.Mockito.mock(com.gtcfesk.exchange.repository.TradingSymbolRepository.class);
        org.mockito.Mockito.when(symbols.findAll()).thenReturn(java.util.Collections.emptyList());
        org.springframework.test.util.ReflectionTestUtils.setField(market,"symbols",symbols);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"redis",org.mockito.Mockito.mock(RedisMarketService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(market,"systemConfigs",configs);
        try {
            market.refreshSymbols();
            java.util.Map<?,?> groups=(java.util.Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(market,"groups");
            Object forex=groups.get("Forex");
            java.util.List<?> codes=(java.util.List<?>)org.springframework.test.util.ReflectionTestUtils.getField(forex,"codes");
            assertTrue(codes.containsAll(java.util.Arrays.asList("CHFUSD=X","HKDUSD=X","CNYUSD=X","SGDUSD=X")));
            org.mockito.Mockito.when(configs.getConfigValue("market.conversion.currencies")).thenReturn("USD,NOK");
            market.refreshSymbols();codes=(java.util.List<?>)org.springframework.test.util.ReflectionTestUtils.getField(forex,"codes");
            assertTrue(codes.contains("NOKUSD=X"));assertFalse(codes.contains("CHFUSD=X"));
            assertTrue(codes.contains("SGDUSD=X")); // Existing fiat settlement remains supported.
        } finally {market.stop();}
    }
    @Test void configurableSettlementHoursAreBounded() {
        assertEquals(8,com.gtcfesk.exchange.admin.SystemConfigService.conversionCacheHours(null));
        assertEquals(1,com.gtcfesk.exchange.admin.SystemConfigService.conversionCacheHours("1"));
        assertEquals(168,com.gtcfesk.exchange.admin.SystemConfigService.conversionCacheHours("168"));
        for(String invalid:new String[]{"", "0", "169", "-1", "1.5", "abc"})
            assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->com.gtcfesk.exchange.admin.SystemConfigService.conversionCacheHours(invalid));
    }
    @Test void cachedRateUsesConfiguredLifetimeAndKeepsMinorUnitScaling() {
        ForexQuoteMarketService market=new ForexQuoteMarketService();
        RedisMarketService redis=org.mockito.Mockito.mock(RedisMarketService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"redis",redis);
        java.util.Map<String,Object> cached=new java.util.HashMap<>();
        cached.put("price",1.25);cached.put("timestamp",System.currentTimeMillis()-60000);
        cached.put("expiresAt",System.currentTimeMillis()+3600000);
        org.mockito.Mockito.when(redis.conversionQuote(org.mockito.ArgumentMatchers.eq("yahoo"),org.mockito.ArgumentMatchers.eq("Forex"),org.mockito.ArgumentMatchers.eq("GBPUSD=X"),org.mockito.ArgumentMatchers.anyMap())).thenReturn(cached);
        try {
            assertEquals(new BigDecimal("0.0125"),market.requireConversionRate("GBp","yahoo"));
            assertEquals(cached.get("expiresAt"),market.conversion("GBp","yahoo").get("conversionExpiresAt"));
            assertFalse((Boolean)market.conversion("JPY","yahoo").get("conversionAvailable"));
            assertEquals(BigDecimal.ONE,market.requireConversionRate("USD","yahoo"));
        } finally {market.stop();}
    }
    @Test void providerRoutesAndMinorUnits() {
        assertTrue(QuoteCurrencyConversion.fixed("USD"));
        assertTrue(QuoteCurrencyConversion.fixed("USDT"));
        assertFalse(QuoteCurrencyConversion.fixed("USDC"));
        assertEquals("USDCUSDT",QuoteCurrencyConversion.route("USDC","binance").code);
        assertEquals("BTCUSDT",QuoteCurrencyConversion.route("BTC","binance").code);
        assertEquals("JPYUSD=X",QuoteCurrencyConversion.route("JPY","yahoo").code);
        for(String currency:new String[]{"GBp","GBX","ZAc","ILA"})
            assertEquals(new BigDecimal("0.01"),QuoteCurrencyConversion.route(currency,"yahoo").scale);
        assertEquals("GBPUSD=X",QuoteCurrencyConversion.route("GBp","yahoo").code);
        assertNull(QuoteCurrencyConversion.route(null,"yahoo"));
        assertNull(QuoteCurrencyConversion.route("../","yahoo"));
    }
}
