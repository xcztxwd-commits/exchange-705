package com.gtcfesk.exchange.demo;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.control.*;
import org.springframework.mock.web.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoTradingTest {
 private TenantContext.Scope tenantScope;
 @AfterEach void closeTenantScope(){ if(tenantScope!=null)tenantScope.close(); }
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final DemoAccountRepository accounts = mock(DemoAccountRepository.class);
    final DemoOrderRepository orders = mock(DemoOrderRepository.class);
    final DemoLedgerRepository ledger = mock(DemoLedgerRepository.class);
    final TradingSymbolRepository symbols = mock(TradingSymbolRepository.class);
    final ForexQuoteMarketService quotes = mock(ForexQuoteMarketService.class);
    final DemoTradingService service = new DemoTradingService(users, accounts, orders, ledger, symbols, quotes);
    final Map<String, DemoOrder> saved = new HashMap<>();
    DemoAccount account; UserAccount user; TradingSymbol symbol;
    Map<String, Object> quote;
    BigDecimal d(String value) { return new BigDecimal(value); }
    void equal(String expected, BigDecimal actual) { assertEquals(0, d(expected).compareTo(actual)); }
    @BeforeEach void setup() {
        tenantScope = TenantContext.open(1L);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"tenantPolicy",mock(TenantPolicyService.class));
        user = new UserAccount(); user.setId(1L); user.setStatus("normal"); user.setKycStatus("NOT_VERIFIED");
        when(users.lockById(1L)).thenReturn(Optional.of(user));
        when(accounts.findByTenantIdAndId(1L, 1L)).thenAnswer(call -> Optional.ofNullable(account));
        when(accounts.save(any())).thenAnswer(call -> account = call.getArgument(0));
        when(orders.findByTenantIdAndUserIdAndRequestKey(org.mockito.ArgumentMatchers.eq(1L), eq(1L), anyString())).thenAnswer(call -> saved.values().stream().filter(o -> o.requestKey.equals(call.getArgument(2))).findFirst());
        when(orders.findByTenantIdAndIdAndUserId(org.mockito.ArgumentMatchers.eq(1L), anyString(), eq(1L))).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(1))));
        when(orders.findByTenantIdAndUserIdAndStatusOrderByCreatedAtDesc(1L, 1L, "OPEN")).thenAnswer(call -> {
            List<DemoOrder> result = new ArrayList<>(); saved.values().stream().filter(o -> o.status.equals("OPEN")).forEach(result::add); return result;
        });
        when(orders.save(any())).thenAnswer(call -> { DemoOrder order = call.getArgument(0); saved.put(order.id, order); return order; });
        symbol = new TradingSymbol(); symbol.setSymbol("BTCUSDT"); symbol.setAlltickSymbol("BTCUSDT");
        symbol.setIsEnabled(true); symbol.setSourceCategory("Crypto"); symbol.setQuoteCurrency("USDT");
        when(symbols.findByTenantIdAndSymbol(1L, "BTCUSDT")).thenReturn(Optional.of(symbol));
        quote = new HashMap<>(); quote.put("price", d("100")); quote.put("timestamp", System.currentTimeMillis());
        quote.put("fetchedAt", System.currentTimeMillis()); quote.put("available", true);
        when(quotes.getPrice("BTCUSDT", "Crypto")).thenReturn(quote);
    }
    DemoOrder buy(String key) { return service.buy(1L, key, 1, "BTCUSDT", d("1000")); }
    @Test void unverifiedInitializationIsIdempotentAndCashConserves() {
        service.initialize(1L); service.initialize(1L); equal("100000", account.cash);
        verify(ledger, times(1)).save(any());
        DemoOrder order = buy("one"); equal("98999", account.cash); equal("10", order.quantity);
        assertSame(order, buy("one")); equal("98999", account.cash);
        quote.put("price", d("110")); service.close(1L, order.id, 1);
        equal("100097.9", account.cash); equal("97.9", order.realizedPnl);
        service.close(1L, order.id, 1); equal("100097.9", account.cash);
        verify(ledger, times(3)).save(any()); verify(quotes, never()).freshPrice(anyString());
    }
    @Test void conflictAndValidationCannotDebit() {
        service.initialize(1L); buy("one");
        assertThrows(BusinessException.class, () -> service.buy(1L, "one", 1, "BTCUSDT", d("2000")));
        for (String amount : Arrays.asList("0", "-1", "9.99", "100000.01", "10.001", "100000"))
            assertThrows(BusinessException.class, () -> service.buy(1L, "bad", 1, "BTCUSDT", d(amount)));
        equal("98999", account.cash);
    }
    @Test void staleAndControlledQuotesNeverExecute() {
        service.initialize(1L); quote.put("timestamp", System.currentTimeMillis() - 61000);
        assertThrows(BusinessException.class, () -> buy("one"));
        quote.put("timestamp", System.currentTimeMillis()); quote.put("available", false);
        assertThrows(BusinessException.class, () -> buy("two"));
        equal("100000", account.cash); assertTrue(saved.isEmpty());
    }
    @Test void anotherOwnerCannotCloseAndFrozenUserCannotRead() {
        service.initialize(1L); DemoOrder order = buy("one");
        UserAccount other = new UserAccount(); other.setStatus("normal");
        when(users.lockById(2L)).thenReturn(Optional.of(other));
        DemoAccount otherAccount = new DemoAccount(); otherAccount.generation = 1;
        when(accounts.findByTenantIdAndId(1L, 2L)).thenReturn(Optional.of(otherAccount));
        assertThrows(BusinessException.class, () -> service.close(2L, order.id, 1));
        user.setStatus("frozen"); assertThrows(BusinessException.class, () -> service.snapshot(1L));
        equal("98999", account.cash);
    }
    @Test void resetPreservesHistoryHasCooldownAndRejectsOldGeneration() {
        service.initialize(1L); DemoOrder order = buy("one");
        assertThrows(BusinessException.class, () -> service.reset(1L, "reset", 1));
        service.close(1L, order.id, 1); service.reset(1L, "reset", 1);
        equal("100000", account.cash); assertEquals(2, account.generation); assertEquals(1, saved.size());
        service.reset(1L, "reset", 1); assertEquals(2, account.generation);
        assertThrows(BusinessException.class, () -> buy("late"));
        assertThrows(BusinessException.class, () -> service.reset(1L, "again", 2));
        account.lastResetAt = Instant.now().minusSeconds(86401); service.reset(1L, "again", 2);
        assertEquals(3, account.generation);
    }
    @Test void staleValuationIsNullNotFalseZero() {
        service.initialize(1L); buy("one"); quote.put("available", false);
        Map<String,Object> snapshot = service.snapshot(1L);
        assertEquals(false, snapshot.get("valuationComplete")); assertNull(snapshot.get("equity"));
        assertNull(snapshot.get("unrealizedPnl"));
    }
    @Test void disabledOrNonSpotInstrumentCannotOpen() {
        service.initialize(1L); symbol.setIsEnabled(false);
        assertThrows(BusinessException.class, () -> buy("one"));
        symbol.setIsEnabled(true); symbol.setSourceCategory("CryptoPerpetual");
        assertThrows(BusinessException.class, () -> buy("one"));
        symbol.setSourceCategory("Crypto"); symbol.setQuoteCurrency("USD");
        assertThrows(BusinessException.class, () -> buy("one"));
        equal("100000", account.cash);
    }
    @Test void demoHeaderBlocksEveryRealBusinessPath() throws Exception {
        DemoModeBoundary boundary = new DemoModeBoundary();
        for (String path : Arrays.asList("/api/transfer/submit", "/api/trade/contract/order", "/api/withdraw/submit", "/api/user/assets", "/api/loan/apply", "/api/market/write")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path); request.addHeader("X-Account-Mode", "DEMO");
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(boundary.preHandle(request, response, null)); assertEquals(409, response.getStatus());
        }
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/demo/account");
        request.addHeader("X-Account-Mode", "DEMO"); MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(boundary.preHandle(request, response, null)); assertEquals("no-store", response.getHeader("Cache-Control"));
    }
}
