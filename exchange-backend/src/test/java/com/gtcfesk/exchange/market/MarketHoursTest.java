package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketHoursTest extends TenantMarketTestContext {
    MarketHoursConfig.Settings s;
    TradingSymbol fx;
    @BeforeEach void setupHours() {
        s=MarketHoursConfig.defaults();fx=new TradingSymbol();fx.setId(1L);fx.setSymbol("EURUSD=X");fx.setCategory("Forex");fx.setSourceCategory("Forex");fx.setIsEnabled(true);
    }
    MarketHoursConfig.Status at(String instant) {return MarketHoursConfig.evaluate(s,fx,Instant.parse(instant));}
    MarketHoursConfig.Rule own(String mode,String until) {
        MarketHoursConfig.Rule r=new MarketHoursConfig.Rule();r.mode=mode;r.until=until;r.reason="测试操作";s.symbols.put("1",r);return r;
    }
    @Test void summerWeekendHalfOpenAndUtc8() {
        assertFalse(at("2026-10-02T20:59:59Z").closed);
        assertTrue(at("2026-10-02T21:00:00Z").closed);
        assertEquals(Instant.parse("2026-10-04T21:00:00Z").toEpochMilli(),at("2026-10-03T00:00:00Z").nextChangeAt);
        assertFalse(at("2026-10-04T21:00:00Z").closed);
    }
    @Test void winterAndBothDstTransitionWeekends() {
        assertFalse(at("2026-01-02T21:59:59Z").closed);assertTrue(at("2026-01-02T22:00:00Z").closed);
        assertEquals(Instant.parse("2026-03-08T21:00:00Z").toEpochMilli(),at("2026-03-06T22:00:00Z").nextChangeAt);
        assertEquals(Instant.parse("2026-11-01T22:00:00Z").toEpochMilli(),at("2026-10-30T21:00:00Z").nextChangeAt);
    }
    @Test void oandaRolloverIsOptionalAndHasDistinctWeekBoundary() {
        assertFalse(at("2026-10-05T20:59:00Z").closed);
        s.categories.get("Forex").strategyId="fx-oanda";
        assertFalse(at("2026-10-05T20:58:59Z").closed);assertTrue(at("2026-10-05T20:59:00Z").closed);
        assertFalse(at("2026-10-05T21:05:00Z").closed);
        assertTrue(at("2026-10-04T21:04:59Z").closed);assertFalse(at("2026-10-04T21:05:00Z").closed);
        assertTrue(at("2026-01-01T16:00:00Z").closed);assertFalse(at("2026-01-01T22:05:00Z").closed);
    }
    @Test void forexFallbackSurvivesDisplayReclassificationAndCryptoIsNotClosed() {
        fx.setCategory("Favorites");assertTrue(at("2026-10-03T12:00:00Z").closed);
        fx.setSourceCategory("Crypto");assertFalse(at("2026-10-03T12:00:00Z").closed);
        own("AUTO",null).strategyId="fx-oanda";assertTrue(at("2026-10-03T12:00:00Z").closed);
    }
    @Test void nzdAdditionalZoneAndTryWorkingHoursAreIndependent() {
        s.categories.get("Forex").strategyId="fx-oanda-nzd";
        assertFalse(at("2026-10-05T17:58:59Z").closed);assertTrue(at("2026-10-05T17:59:00Z").closed);
        assertFalse(at("2026-10-05T18:05:00Z").closed);assertTrue(at("2026-10-05T20:59:00Z").closed);
        assertTrue(at("2026-08-03T18:59:00Z").closed); // Auckland standard time, New York daylight time.
        s.categories.get("Forex").strategyId="fx-oanda-try";
        assertTrue(at("2026-10-05T05:59:59Z").closed);assertFalse(at("2026-10-05T06:00:00Z").closed);
        assertTrue(at("2026-10-05T15:00:00Z").closed);assertTrue(at("2026-10-03T12:00:00Z").closed);
    }
    @Test void manualPrecedenceAndExpiry() {
        own("OPEN","2026-10-03T13:00:00Z");assertFalse(at("2026-10-03T12:00:00Z").closed);
        assertTrue(at("2026-10-03T13:00:00Z").closed);
        MarketHoursConfig.Rule c=s.categories.get("Forex");c.mode="CLOSED";c.reason="分类维护";
        assertTrue(at("2026-10-03T12:00:00Z").closed);
        c.mode="OPEN";c.until="2026-10-05T13:00:00Z";own("CLOSED",null);
        assertTrue(at("2026-10-05T12:00:00Z").closed);
    }
    @Test void explicitHolidayCanCloseWeekdayOrOpenWeekend() {
        MarketHoursConfig.ExceptionRange e=new MarketHoursConfig.ExceptionRange();e.start="2026-10-03T10:00:00Z";e.end="2026-10-03T11:00:00Z";e.reason="核验开市";e.source="执行商公告";e.closed=false;s.strategies.get(0).exceptions.add(e);
        assertTrue(at("2026-10-03T09:59:59Z").closed);assertFalse(at(e.start).closed);assertTrue(at(e.end).closed);
        e.start="2026-10-05T10:00:00Z";e.end="2026-10-05T11:00:00Z";e.closed=true;
        assertTrue(at(e.start).closed);assertFalse(at(e.end).closed);
    }
    @Test void repeatedDstHourUsesFirstCloseAndLastReopen() {
        MarketHoursConfig.Strategy p=new MarketHoursConfig.Strategy();p.id="overlap";p.name="夏令时重叠";
        p.weekly.add(new MarketHoursConfig.Weekly(7,"01:30",7,"01:45","测试"));s.strategies.add(p);s.categories.get("Forex").strategyId=p.id;
        assertFalse(at("2026-11-01T05:29:59Z").closed);assertTrue(at("2026-11-01T05:30:00Z").closed);
        assertTrue(at("2026-11-01T06:30:00Z").closed);assertFalse(at("2026-11-01T06:45:00Z").closed);
    }
    @Test void unionIntervalsAndLongExceptionHaveAccurateNextChange() {
        s.strategies.get(0).weekly.add(new MarketHoursConfig.Weekly(7,"16:00",7,"18:00","延期"));
        assertEquals(Instant.parse("2026-10-04T22:00:00Z").toEpochMilli(),at("2026-10-03T00:00:00Z").nextChangeAt);
        MarketHoursConfig.ExceptionRange e=new MarketHoursConfig.ExceptionRange();e.start="2026-10-01T00:00:00Z";e.end="2026-12-05T12:00:00Z";e.reason="长维护";e.source="公告";s.strategies.get(0).exceptions.add(e);
        assertEquals(Instant.parse("2026-12-06T23:00:00Z").toEpochMilli(),at("2026-10-03T00:00:00Z").nextChangeAt);
    }
    @Test void invalidDataCannotSilentlyFallBackToOpen() {
        assertThrows(BusinessException.class,()->MarketHoursConfig.parse(""));
        assertThrows(BusinessException.class,()->MarketHoursConfig.parse("{\"version\":true}"));
        assertThrows(BusinessException.class,()->MarketHoursConfig.parse("{\"version\":1,\"unknown\":0}"));
        s.strategies.get(0).timezone="Not/AZone";assertThrows(BusinessException.class,()->MarketHoursConfig.validate(s));
        s=MarketHoursConfig.defaults();own("OPEN",null);assertThrows(BusinessException.class,()->MarketHoursConfig.validate(s));
        s=MarketHoursConfig.defaults();s.strategies.get(0).weekly.get(0).startTime="24:00";assertThrows(BusinessException.class,()->MarketHoursConfig.validate(s));
    }
    @Test void overlappingExceptionsAreRejectedAndRoundTripIsValid() {
        String raw=MarketHoursConfig.encode(s);assertEquals(raw,MarketHoursConfig.encode(MarketHoursConfig.parse(raw)));
        MarketHoursConfig.ExceptionRange e=s.strategies.get(1).exceptions.get(0);s.strategies.get(1).exceptions.add(e);
        assertThrows(BusinessException.class,()->MarketHoursConfig.validate(s));
    }
    @Test void cachedSettingsNeverCrossTenantBoundary() {
        SystemConfigService configs=mock(SystemConfigService.class);
        MarketHoursService hours=new MarketHoursService(configs,mock(com.gtcfesk.exchange.repository.TradingSymbolRepository.class),mock(MarketCategoryService.class),mock(com.gtcfesk.exchange.control.TenantRepository.class));
        when(configs.getConfigValue(MarketHoursConfig.KEY)).thenAnswer(invocation->{MarketHoursConfig.Settings settings=MarketHoursConfig.defaults();MarketHoursConfig.Rule r=new MarketHoursConfig.Rule();r.mode=TenantContext.requireTenantId()==1L?"CLOSED":"OPEN";r.reason="维护";r.until=Instant.now().plusSeconds(3600).toString();settings.categories.put("Forex",r);return MarketHoursConfig.encode(settings);});
        assertTrue(hours.status(fx).closed);
        TenantContext.clear();
        try(TenantContext.Scope ignored=TenantContext.open(2L)){assertFalse(hours.status(fx).closed);}
        try(TenantContext.Scope ignored=TenantContext.open(1L)){assertTrue(hours.status(fx).closed);}
        TenantContext.open(1L);verify(configs,times(2)).getConfigValue(MarketHoursConfig.KEY);
    }
    @Test void calendarBlocksEvenControlledAndSimulatedQuotes() {
        PriceControlTest fixture=new PriceControlTest();fixture.setup();
        try {
            MarketHoursService hours=mock(MarketHoursService.class);MarketHoursConfig.Status closed=new MarketHoursConfig.Status();closed.closed=true;closed.reason="维护";
            when(hours.status(any())).thenReturn(closed);ReflectionTestUtils.setField(fixture.market,"marketHours",hours);
            fixture.saved.get().setControlEnabled(true);fixture.saved.get().setControlPriceOffset(java.math.BigDecimal.ONE);fixture.market.refreshSymbols();
            Map<String,Object> quote=fixture.market.internalPrice("TEST");assertEquals("closed",quote.get("status"));assertEquals(false,quote.get("available"));assertNull(fixture.market.freshPrice("TEST"));
            ReflectionTestUtils.setField(fixture.market,"virtualTrading",true);fixture.market.randomMarket(1L,true,null);
            assertEquals("closed",fixture.market.internalPrice("TEST").get("status"));assertNull(fixture.market.freshPrice("TEST"));
            closed.closed=false;assertNotNull(fixture.market.freshPrice("TEST"));
        } finally {fixture.stop();}
    }
    @Test void forcedOpenDoesNotReviveExpiredSourceQuote() {
        PriceControlTest fixture=new PriceControlTest();fixture.setup();
        try {
            MarketHoursService hours=mock(MarketHoursService.class);when(hours.status(any())).thenReturn(new MarketHoursConfig.Status());
            ReflectionTestUtils.setField(fixture.market,"marketHours",hours);
            fixture.source.get("SOURCE").put("timestamp",System.currentTimeMillis()-120000);
            assertFalse((Boolean)fixture.market.internalPrice("TEST").get("marketClosed"));assertNull(fixture.market.freshPrice("TEST"));
        }finally{fixture.stop();}
    }
    @Test void writesEnforceTargetVersionAndOpeningLimitsThenInvalidateCacheAfterCommit() {
        s.categories.get("Forex").strategyId="always-open";
        java.util.concurrent.atomic.AtomicReference<String> stored=new java.util.concurrent.atomic.AtomicReference<>(MarketHoursConfig.encode(s));
        SystemConfigService configs=mock(SystemConfigService.class);when(configs.getConfigValue(MarketHoursConfig.KEY)).thenAnswer(i->stored.get());
        doAnswer(i->{stored.set(i.getArgument(1));return null;}).when(configs).saveConfig(anyString(),anyString(),anyString());
        com.gtcfesk.exchange.repository.TradingSymbolRepository symbols=mock(com.gtcfesk.exchange.repository.TradingSymbolRepository.class);when(symbols.findAllByTenantId(1L)).thenReturn(Collections.singletonList(fx));
        MarketCategoryService categories=mock(MarketCategoryService.class);when(categories.all()).thenReturn(Collections.singletonList(Collections.singletonMap("key","Forex")));
        com.gtcfesk.exchange.control.TenantRepository tenants=mock(com.gtcfesk.exchange.control.TenantRepository.class);when(tenants.lock(1L)).thenReturn(Optional.of(new com.gtcfesk.exchange.control.Tenant()));
        MarketHoursService hours=new MarketHoursService(configs,symbols,categories,tenants);
        MarketHoursService.OverrideInput input=new MarketHoursService.OverrideInput();input.scope="SYMBOL";input.target="999";input.mode="CLOSED";input.reason="维护";
        assertThrows(BusinessException.class,()->hours.override(input));
        input.target="1";input.revision=1;assertThrows(BusinessException.class,()->hours.override(input));
        input.revision=0;input.mode="OPEN";assertThrows(BusinessException.class,()->hours.override(input));
        input.until=Instant.now().plusSeconds(86401).toString();assertThrows(BusinessException.class,()->hours.override(input));
        MarketHoursConfig.Settings edit=MarketHoursConfig.defaults();edit.categories.get("Forex").mode="CLOSED";edit.categories.get("Forex").reason="绕过开关权限";
        assertThrows(BusinessException.class,()->hours.save(edit));verify(configs,never()).saveConfig(anyString(),anyString(),anyString());
        assertFalse(hours.status(fx).closed);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            input.mode="CLOSED";input.until=null;
            Map<String,Object> result=hours.override(input);
            assertEquals(1,((MarketHoursConfig.Settings)result.get("settings")).revision);
            assertEquals("CLOSED",MarketHoursConfig.parse(stored.get()).symbols.get("1").mode);
            for(org.springframework.transaction.support.TransactionSynchronization sync:org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations())sync.afterCommit();
            assertTrue(hours.status(fx).closed);
        } finally {org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
        verify(configs,times(1)).saveConfig(eq(MarketHoursConfig.KEY),anyString(),anyString());
    }
    @Test void termCannotReachNextPlannedClosure() {
        MarketHoursService hours=spy(new MarketHoursService(null,null,null,null));
        MarketHoursConfig.Status status=new MarketHoursConfig.Status();status.nextChangeAt=Instant.now().plusSeconds(120).toEpochMilli();
        doReturn(status).when(hours).status(fx);
        assertDoesNotThrow(()->hours.requireWindow(fx,30));assertThrows(BusinessException.class,()->hours.requireWindow(fx,180));
        status.closed=true;status.reason="维护";assertThrows(BusinessException.class,()->hours.requireWindow(fx,30));
    }
}
