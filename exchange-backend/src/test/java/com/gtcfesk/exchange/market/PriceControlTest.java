package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriceControlTest {
    final ForexQuoteMarketService market = new ForexQuoteMarketService();
    final TradingSymbolRepository repository = mock(TradingSymbolRepository.class);
    final AtomicReference<TradingSymbol> saved = new AtomicReference<>();
    Map<String, Map<String, Object>> source;

    TradingSymbol copy(TradingSymbol value) { TradingSymbol copy = new TradingSymbol(); BeanUtils.copyProperties(value, copy); return copy; }
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        TradingSymbol symbol = new TradingSymbol(); symbol.setId(1L); symbol.setSymbol("TEST"); symbol.setCategory("Metal");
        symbol.setName("Test"); symbol.setBaseCurrency("TEST"); symbol.setAlltickSymbol("SOURCE"); saved.set(symbol);
        when(repository.findAll()).thenAnswer(call -> Collections.singletonList(copy(saved.get())));
        when(repository.findById(1L)).thenAnswer(call -> Optional.of(copy(saved.get())));
        when(repository.saveAndFlush(any())).thenAnswer(call -> { saved.set(copy(call.getArgument(0))); return copy(saved.get()); });
        ReflectionTestUtils.setField(market, "symbols", repository);
        ReflectionTestUtils.setField(market, "redis", mock(RedisMarketService.class));
        Map<String, Object> groups = (Map<String, Object>) ReflectionTestUtils.getField(market, "groups");
        source = (Map<String, Map<String, Object>>) ReflectionTestUtils.getField(groups.get("Metal"), "quotes");
        raw(90); market.refreshSymbols();
    }
    @AfterEach void stop() { market.stop(); }
    void raw(double price) {
        Map<String, Object> quote = new HashMap<>(); quote.put("price", price); quote.put("timestamp", System.currentTimeMillis());
        quote.put("fetchedAt", System.currentTimeMillis()); quote.put("sourceAvailable", true); source.put("SOURCE", quote);
    }
    void elapsed(int seconds) { saved.get().setControlStartedAt(System.currentTimeMillis() - seconds * 1000L); market.refreshSymbols(); }
    void price(String expected) { assertEquals(0, new BigDecimal(expected).compareTo(market.freshPrice("TEST"))); }

    @Test void schedulerCompletesTargetAndRestoreWithoutManualCompletionCalls() throws Exception {
        MarketQuoteSource provider = mock(MarketQuoteSource.class);
        when(provider.getBatchPrices(anyList(), anyString())).thenAnswer(call -> {
            Map<String, Object> quote = new HashMap<>();
            quote.put("price", 90d); quote.put("timestamp", System.currentTimeMillis());
            return Collections.singletonMap("SOURCE", quote);
        });
        ReflectionTestUtils.setField(market, "source", provider);
        ReflectionTestUtils.setField(market, "http", mock(MarketHttp.class));
        market.start();
        market.startControl(1L, 2, new BigDecimal("100"), 5, true);
        awaitCompletion(); price("100");
        assertTrue(saved.get().getControlEnabled());
        assertEquals(0, BigDecimal.TEN.compareTo(saved.get().getControlPriceOffset()));
        market.restoreControl(1L, 2, 5, true);
        awaitCompletion(); price("90");
        assertFalse(saved.get().getControlEnabled());
        assertEquals(0, saved.get().getControlPriceOffset().signum());
    }

    void awaitCompletion() throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
        while (PriceControlPath.running(saved.get()) && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(PriceControlPath.running(saved.get()), "scheduler did not finish the task");
    }

    @Test void disabledOscillationIsLinearEvenAtMaximumIntensityInBothDirections() {
        market.startControl(1L, 10, new BigDecimal("100"), 10, false);
        TradingSymbol plan = saved.get(); long start = plan.getControlStartedAt();
        for (int second = 0; second <= 10; second++)
            assertEquals(0, BigDecimal.valueOf(90 + second).compareTo(PriceControlPath.price(plan, start + second * 1000)));
        assertEquals(0, new BigDecimal("90").compareTo(PriceControlPath.price(plan, start + 999)));
        plan.setControlTargetPrice(new BigDecimal("80"));
        for (int second = 0; second <= 10; second++)
            assertEquals(0, BigDecimal.valueOf(90 - second).compareTo(PriceControlPath.price(plan, start + second * 1000)));
        plan.setControlRandomOscillation(null);
        assertEquals(0, new BigDecimal("85").compareTo(PriceControlPath.price(plan, start + 5000)));
    }

    @Test void higherIntensityIncreasesRepeatableNoiseWithoutChangingEndpoints() {
        market.startControl(1L, 10, new BigDecimal("100"), 10, true);
        TradingSymbol plan = saved.get(); plan.setControlStartedAt(1700000000000L);
        BigDecimal previous = plan.getControlStartPrice(); boolean fell = false, rose = false;
        for (int second = 1; second < 10; second++) {
            long time = plan.getControlStartedAt() + second * 1000;
            BigDecimal strong = PriceControlPath.price(plan, time);
            assertEquals(strong, PriceControlPath.price(copy(plan), time + 999));
            fell |= strong.compareTo(previous) < 0; rose |= strong.compareTo(previous) > 0; previous = strong;
            TradingSymbol mild = copy(plan); mild.setControlIntensity(1);
            BigDecimal base = BigDecimal.valueOf(90 + second);
            assertTrue(strong.subtract(base).abs().compareTo(PriceControlPath.price(mild, time).subtract(base).abs()) >= 0);
        }
        assertTrue(fell && rose);
        assertEquals(plan.getControlStartPrice(), PriceControlPath.price(plan, plan.getControlStartedAt()));
        assertEquals(plan.getControlTargetPrice(), PriceControlPath.price(plan, PriceControlPath.endsAt(plan)));
        plan.setControlStartPrice(new BigDecimal("0.01")); plan.setControlTargetPrice(new BigDecimal("0.02"));
        for (int second = 1; second < 10; second++) assertTrue(PriceControlPath.price(plan, plan.getControlStartedAt() + second * 1000).signum() > 0);
    }

    @Test void enabledOscillationRisesAndFallsAtEveryIntensityInEitherDirection() {
        market.startControl(1L, 10, new BigDecimal("100"), 1, true);
        TradingSymbol plan = saved.get(); plan.setControlStartedAt(1700000000000L);
        for (int target : new int[]{80, 90, 100}) {
            plan.setControlTargetPrice(BigDecimal.valueOf(target));
            for (int intensity = 1; intensity <= 10; intensity++) {
                plan.setControlIntensity(intensity);
                BigDecimal previous = plan.getControlStartPrice(); boolean fell = false, rose = false;
                for (int second = 1; second <= 10; second++) {
                    BigDecimal price = PriceControlPath.price(plan, plan.getControlStartedAt() + second * 1000);
                    fell |= price.compareTo(previous) < 0; rose |= price.compareTo(previous) > 0; previous = price;
                }
                assertTrue(fell && rose, "target=" + target + ", intensity=" + intensity);
                assertEquals(plan.getControlTargetPrice(), previous);
            }
        }
    }

    @Test void oscillationSurvivesReloadAndAlsoAppliesDuringRestore() {
        assertEquals(true, market.startControl(1L, 10, new BigDecimal("100"), 1, true).get("randomOscillation"));
        elapsed(5);
        BigDecimal expected = PriceControlPath.price(saved.get(), System.currentTimeMillis());
        raw(92); price(expected.toPlainString()); market.refreshSymbols(); price(expected.toPlainString());
        assertEquals(true, market.controlStatus(1L).get("randomOscillation"));
        for (int direction : new int[]{1, -1}) {
            raw(90); market.manualControl(1L, true, BigDecimal.valueOf(direction * 10));
            assertEquals(true, market.restoreControl(1L, 10, 5, true).get("randomOscillation"));
            price(direction == 1 ? "100" : "80");
            elapsed(5); raw(93);
            expected = new BigDecimal("93").add(PriceControlPath.restoreOffset(saved.get(), System.currentTimeMillis()));
            price(expected.toPlainString()); market.refreshSymbols(); price(expected.toPlainString());
            assertTrue(saved.get().getControlRandomOscillation());
            elapsed(10); raw(96); price("96"); market.completeControls();
            assertFalse(saved.get().getControlEnabled()); assertEquals(0, saved.get().getControlPriceOffset().signum());
        }
        market.manualControl(1L, false, BigDecimal.ZERO);
        assertFalse(saved.get().getControlRandomOscillation());
    }

    @Test void targetIgnoresSourceMovementThenRetainsFinalOffsetAndSurvivesReload() {
        market.startControl(1L, 10, new BigDecimal("100"), 1, false);
        elapsed(5); raw(92); price("95");
        market.refreshSymbols(); price("95");
        assertEquals(market.freshPrice("TEST"), market.freshPrice("SOURCE"));
        elapsed(10); raw(93); price("100"); market.completeControls();
        assertFalse(PriceControlPath.running(saved.get())); assertTrue(saved.get().getControlEnabled());
        assertEquals(0, new BigDecimal("7").compareTo(saved.get().getControlPriceOffset()));
        raw(94); price("101");
        market.manualControl(1L, false, BigDecimal.ZERO); price("94");
        assertNull(saved.get().getControlStartedAt());
    }

    @Test void gradualRestoreFollowsMovingSourceForPositiveAndNegativeOffsets() {
        for (int direction : new int[]{1, -1}) {
            raw(90); market.manualControl(1L, true, BigDecimal.valueOf(direction * 10));
            market.restoreControl(1L, 10, 10, false); price(direction == 1 ? "100" : "80");
            elapsed(5); raw(93); price(direction == 1 ? "98" : "88");
            elapsed(10); raw(96); price("96"); market.completeControls();
            assertFalse(saved.get().getControlEnabled()); assertEquals(0, saved.get().getControlPriceOffset().signum());
            raw(98); price("98");
        }
    }

    @Test void restoreCanTakeOverRunningTargetAndStopKeepsCurrentOffset() {
        market.startControl(1L, 10, new BigDecimal("100"), 1, false); elapsed(5); price("95");
        market.restoreControl(1L, 10, 1, false); price("95");
        assertTrue(saved.get().getControlRestoring());
        elapsed(5); price("92.5"); market.stopControl(1L); price("92.5");
        assertFalse(PriceControlPath.running(saved.get())); raw(91); price("93.5");
    }

    @Test void expiredSourceCannotExecuteOrCompleteAndImmediateRestoreStillWorks() {
        market.startControl(1L, 10, new BigDecimal("100"), 10, true); elapsed(10);
        Map<String, Object> quote = source.get("SOURCE"); quote.put("timestamp", System.currentTimeMillis() - 60000);
        long timestamp = QuoteState.time(quote.get("timestamp"));
        assertNull(market.freshPrice("TEST")); market.completeControls(); assertTrue(PriceControlPath.running(saved.get()));
        assertEquals(timestamp, QuoteState.time(market.internalPrice("TEST").get("timestamp")));
        assertThrows(BusinessException.class, () -> market.restoreControl(1L, 10, 1, true));
        market.manualControl(1L, false, BigDecimal.ZERO);
        assertFalse(saved.get().getControlEnabled()); assertNull(market.freshPrice("TEST"));
        raw(91); price("91");
    }

    @Test void validatesParametersAndDoesNotExposeOrAcceptPlansOnPublicSymbolJson() throws Exception {
        assertThrows(BusinessException.class, () -> market.startControl(1L, 0, BigDecimal.TEN, 1, false));
        assertThrows(BusinessException.class, () -> market.startControl(1L, 10, BigDecimal.ZERO, 1, false));
        assertThrows(BusinessException.class, () -> market.startControl(1L, 10, new BigDecimal("1.001"), 1, true));
        assertThrows(BusinessException.class, () -> market.startControl(1L, 10, BigDecimal.TEN, 11, true));
        assertThrows(BusinessException.class, () -> market.manualControl(1L, true, new BigDecimal("-90")));
        market.startControl(1L, 10, new BigDecimal("100"), 1, true);
        assertThrows(BusinessException.class, () -> market.startControl(1L, 10, new BigDecimal("101"), 1, true));
        ObjectMapper mapper = new ObjectMapper();
        assertFalse(mapper.valueToTree(saved.get()).has("controlTargetPrice"));
        assertFalse(mapper.valueToTree(saved.get()).has("controlRandomOscillation"));
        assertNull(mapper.readValue("{\"controlStartedAt\":1,\"controlTargetPrice\":100}", TradingSymbol.class).getControlStartedAt());
        assertNull(mapper.readValue("{\"controlRandomOscillation\":true}", TradingSymbol.class).getControlRandomOscillation());
    }
}
