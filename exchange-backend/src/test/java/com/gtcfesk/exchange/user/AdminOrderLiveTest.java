package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.AdminOrderController;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.trade.ContractOrderService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(DepositOrderServiceTest.Config.class)
@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class AdminOrderLiveTest {
    @Autowired ContractOrderRepository orders;
    @Autowired OptionOrderRepository options;
    @Autowired UserAccountRepository users;
    @Autowired AssetAccountRepository assets;
    ForexQuoteMarketService market;
    JwtUtil jwt;
    AdminOrderController controller;

    @BeforeEach void setup() {
        market = mock(ForexQuoteMarketService.class); jwt = mock(JwtUtil.class);
        controller = new AdminOrderController(orders, options, mock(ContractOrderService.class), users, assets, jwt);
        ReflectionTestUtils.setField(controller, "market", market);
        long now = System.currentTimeMillis();
        when(market.internalPrice("JPY=X")).thenReturn(Map.of("price", new BigDecimal("151"),
                "timestamp", now, "expiresAt", now + 60000, "available", true));
        when(market.contractConversion("JPY", "yahoo")).thenReturn(Map.of("conversionAvailable", true,
                "quoteToUsdRate", new BigDecimal("0.01"), "conversionExpiresAt", now + 60000));
    }

    ContractOrder order(Long user, String status) {
        ContractOrder row = new ContractOrder(); row.setUserId(user); row.setSymbol("JPY=X");
        row.setStatus(status); row.setSide("BUY"); row.setType("MARKET"); row.setQuantity(BigDecimal.ONE);
        row.setOpenPrice(new BigDecimal("150")); row.setCurrentPrice(new BigDecimal("150"));
        row.setLotSize(new BigDecimal("1000")); row.setLeverage(new BigDecimal("100"));
        row.setQuoteCurrency("JPY"); row.setQuoteSource("yahoo"); row.setFee(new BigDecimal("2"));
        row.setMargin(new BigDecimal("10")); row.setProfit(BigDecimal.ZERO);
        return orders.saveAndFlush(row);
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> live(List<Long> ids, String token) {
        return (List<Map<String, Object>>) ((Map<?, ?>) controller.liveContractOrders(Map.of("ids", ids), token).getBody()).get("list");
    }
    Map<String, Object> row(List<Map<String, Object>> rows, Long id) {
        return rows.stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
    }
    void amount(String expected, Object actual) { assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual)); }

    @Test void currentPageUsesSharedQuotesExactFxAndOriginalFeesWithoutWriting() {
        ContractOrder buy = order(null, "OPEN"), sell = order(null, "OPEN"), legacy = order(null, "OPEN");
        sell.setSide("SELL"); orders.saveAndFlush(sell);
        legacy.setLotSize(null); legacy.setLeverage(new BigDecimal("2")); orders.saveAndFlush(legacy);
        ContractOrder pending = order(null, "PENDING"), closed = order(null, "CLOSED"), cancelled = order(null, "CANCELLED");
        closed.setProfit(new BigDecimal("123.45")); closed.setClosePrice(new BigDecimal("149")); orders.saveAndFlush(closed);
        ContractOrder otherPage = order(null, "OPEN");
        List<Map<String, Object>> result = live(Arrays.asList(buy.getId(), sell.getId(), legacy.getId(), pending.getId(), closed.getId(), cancelled.getId()), null);
        assertEquals(6, result.size()); assertFalse(result.stream().anyMatch(r -> otherPage.getId().equals(r.get("id"))));
        amount("10", row(result, buy.getId()).get("profit")); amount("8", row(result, buy.getId()).get("netProfit"));
        amount("-10", row(result, sell.getId()).get("profit")); amount("-12", row(result, sell.getId()).get("netProfit"));
        amount("0.02", row(result, legacy.getId()).get("profit")); amount("0.02", row(result, legacy.getId()).get("netProfit"));
        amount("0", row(result, pending.getId()).get("netProfit")); amount("0", row(result, cancelled.getId()).get("netProfit"));
        amount("123.45", row(result, closed.getId()).get("profit")); amount("121.45", row(result, closed.getId()).get("netProfit"));
        amount("149", row(result, closed.getId()).get("currentPrice"));
        verify(market, times(1)).internalPrice("JPY=X"); verify(market, times(1)).contractConversion("JPY", "yahoo");
        ContractOrder recorded = orders.findByTenantIdAndId(1L, buy.getId()).orElseThrow();
        amount("0", recorded.getProfit()); amount("150", recorded.getCurrentPrice());
        assertEquals(buy.getRowVersion(), recorded.getRowVersion());
    }

    @Test void missingOrExpiredInputsAreUnavailableAndSettledRowsNeverRevalue() {
        ContractOrder open = order(null, "OPEN");
        when(market.contractConversion("JPY", "yahoo")).thenReturn(Map.of("conversionAvailable", false));
        assertEquals(false, row(live(List.of(open.getId()), null), open.getId()).get("liveAvailable"));
        when(market.contractConversion("JPY", "yahoo")).thenReturn(Map.of("conversionAvailable", true,
                "quoteToUsdRate", BigDecimal.ONE, "conversionExpiresAt", System.currentTimeMillis() - 1));
        assertEquals(false, row(live(List.of(open.getId()), null), open.getId()).get("liveAvailable"));
        when(market.internalPrice("JPY=X")).thenReturn(Map.of("price", new BigDecimal("151"),
                "timestamp", System.currentTimeMillis() - 120000, "expiresAt", 1L, "available", true));
        assertEquals(false, row(live(List.of(open.getId()), null), open.getId()).get("liveAvailable"));
        reset(market);
        ContractOrder closed = order(null, "CLOSED"); closed.setDeletedAt(java.time.LocalDateTime.now()); orders.saveAndFlush(closed);
        assertEquals(true, row(live(List.of(closed.getId()), null), closed.getId()).get("liveAvailable"));
        verifyNoInteractions(market);
    }

    @Test void idsStayWithinTenantAndAgentVisibility() {
        UserAccount own = new UserAccount(); own.setEmail(UUID.randomUUID() + "@fixture.invalid"); own.setPasswordHash("fixture");
        own.setParentUserId(42L); users.saveAndFlush(own);
        UserAccount outside = new UserAccount(); outside.setEmail(UUID.randomUUID() + "@fixture.invalid"); outside.setPasswordHash("fixture"); users.saveAndFlush(outside);
        ContractOrder visible = order(own.getId(), "OPEN"), hidden = order(outside.getId(), "OPEN"), unbound = order(null, "OPEN"), otherTenant;
        TenantContext.clear();
        try (TenantContext.Scope ignored = TenantContext.open(2L)) { otherTenant = order(null, "OPEN"); }
        finally { TenantContext.open(1L); }
        when(jwt.parse("agent")).thenReturn(io.jsonwebtoken.Jwts.claims().setSubject("agent-42"));
        List<Long> ids = Arrays.asList(visible.getId(), visible.getId(), hidden.getId(), unbound.getId(), otherTenant.getId());
        List<Map<String, Object>> agent = live(ids, "Bearer agent");
        assertEquals(1, agent.size()); assertEquals(visible.getId(), agent.get(0).get("id"));
        List<Map<String, Object>> admin = live(ids, null);
        assertEquals(3, admin.size()); assertFalse(admin.stream().anyMatch(r -> otherTenant.getId().equals(r.get("id"))));
    }

    @Test void emptyAndInvalidBatchesCannotBecomeUnboundedReads() throws Exception {
        ContractOrderRepository repository = mock(ContractOrderRepository.class);
        AdminOrderController bounded = new AdminOrderController(repository, options, mock(ContractOrderService.class), users, assets, jwt);
        assertEquals(List.of(), ((Map<?, ?>) bounded.liveContractOrders(Map.of("ids", List.of()), null).getBody()).get("list"));
        assertThrows(BusinessException.class, () -> bounded.liveContractOrders(Map.of(), null));
        assertThrows(BusinessException.class, () -> bounded.liveContractOrders(Map.of("ids", Collections.nCopies(101, 1L)), null));
        for (Object invalid : Arrays.asList(0, -1, "1.5", "bad"))
            assertThrows(BusinessException.class, () -> bounded.liveContractOrders(Map.of("ids", List.of(invalid)), null));
        verifyNoInteractions(repository);
        java.lang.reflect.Method method = AdminOrderController.class.getMethod("liveContractOrders", Map.class, String.class);
        assertEquals("orders", method.getAnnotation(com.gtcfesk.exchange.config.AdminPermission.class).menu());
        org.springframework.transaction.annotation.Transactional transaction = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertTrue(transaction.readOnly()); assertEquals(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ, transaction.isolation());
        assertEquals("no-store", bounded.liveContractOrders(Map.of("ids", List.of()), null).getHeaders().getCacheControl());
    }
}
