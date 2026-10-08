package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.AdminOrderController;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.entity.OptionOrder;
import com.gtcfesk.exchange.entity.OptionDuration;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.trade.ContractOrderService;
import com.gtcfesk.exchange.trade.OptionOrderService;
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
    OptionDurationRepository durations;

    @BeforeEach void setup() {
        market = mock(ForexQuoteMarketService.class); jwt = mock(JwtUtil.class);
        controller = new AdminOrderController(orders, options, mock(ContractOrderService.class), users, assets, jwt);
        ReflectionTestUtils.setField(controller, "market", market);
        durations = mock(OptionDurationRepository.class);
        ReflectionTestUtils.setField(controller, "optionDurations", durations);
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

    OptionOrder option(Long user, String direction) {
        OptionOrder row = new OptionOrder(); row.setUserId(user); row.setSymbol("JPY=X"); row.setDirection(direction);
        row.setAmount(new BigDecimal("100")); row.setOpenPrice(new BigDecimal("150")); row.setProfit(BigDecimal.ZERO);
        row.setDuration(60); row.setStatus("TRADING"); return options.saveAndFlush(row);
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> optionLive(List<Long> ids, String token) {
        return (List<Map<String, Object>>) ((Map<?, ?>) controller.liveOptionOrders(Map.of("ids", ids), token).getBody()).get("list");
    }

    @Test void optionEstimatesUseSettlementRatesPresetsAndFreshQuotesWithoutWriting() {
        OptionDuration duration = new OptionDuration(); duration.setProfitRate(new BigDecimal("0.63")); duration.setLossRate(new BigDecimal("0.7"));
        when(durations.findByTenantIdAndDuration(1L, 60)).thenReturn(Optional.of(duration));
        OptionOrder up = option(1L, "UP"), down = option(1L, "DOWN"), preset = option(1L, "DOWN");
        preset.setPresetProfitType("PROFIT"); options.saveAndFlush(preset);
        List<Map<String, Object>> result = optionLive(List.of(up.getId(), down.getId(), preset.getId()), null);
        amount("151", row(result, up.getId()).get("currentPrice"));
        amount("63", row(result, up.getId()).get("profit")); amount("-70", row(result, down.getId()).get("profit"));
        amount("63", row(result, preset.getId()).get("profit"));
        verify(market, times(1)).internalPrice("JPY=X"); verify(durations, times(1)).findByTenantIdAndDuration(1L, 60);
        OptionOrder recorded = options.findByTenantIdAndId(1L, up.getId()).orElseThrow();
        amount("0", recorded.getProfit()); assertEquals(up.getRowVersion(), recorded.getRowVersion()); assertNull(recorded.getClosePrice());
        for (String direction : List.of("UP", "DOWN")) {
            up.setDirection(direction);
            for (String price : List.of("149", "150", "151")) {
                String expected = direction.equals("UP") && price.equals("151") || direction.equals("DOWN") && price.equals("149") ? "63" : "-70";
                amount(expected, OptionOrderService.calculateProfit(up, new BigDecimal(price), duration));
            }
        }
        up.setPresetProfitType("LOSS"); amount("-70", OptionOrderService.calculateProfit(up, new BigDecimal("151"), duration));
        up.setPresetProfitType(null); up.setDirection("UP"); amount("80", OptionOrderService.calculateProfit(up, new BigDecimal("151"), null));
    }

    @Test void optionUnavailableQuotesAndTerminalOrdersNeverBecomeNewValuations() {
        OptionOrder order = option(1L, "UP");
        when(market.internalPrice("JPY=X")).thenReturn(Map.of("price", new BigDecimal("151"), "available", true, "expiresAt", 1L, "timestamp", 1L));
        assertEquals(false, row(optionLive(List.of(order.getId()), null), order.getId()).get("liveAvailable"));
        reset(market, durations); order.setStatus("CLOSED"); order.setProfit(new BigDecimal("45")); order.setClosePrice(new BigDecimal("153")); options.saveAndFlush(order);
        Map<String, Object> result = row(optionLive(List.of(order.getId()), null), order.getId());
        amount("45", result.get("profit")); amount("153", result.get("currentPrice")); verifyNoInteractions(market, durations);
    }

    @Test void optionMonitorIdsKeepTenantAndAgentScopeAndReadOnlyBatchPolicy() throws Exception {
        UserAccount own = new UserAccount(); own.setEmail(UUID.randomUUID() + "@fixture.invalid"); own.setPasswordHash("fixture"); own.setParentUserId(42L); users.saveAndFlush(own);
        OptionOrder visible = option(own.getId(), "UP"), hidden = option(own.getId() + 100000, "DOWN"), otherTenant;
        TenantContext.clear();
        try (TenantContext.Scope ignored = TenantContext.open(2L)) { otherTenant = option(1L, "UP"); }
        finally { TenantContext.open(1L); }
        when(jwt.parse("agent")).thenReturn(io.jsonwebtoken.Jwts.claims().setSubject("agent-42"));
        List<Long> ids = List.of(visible.getId(), visible.getId(), hidden.getId(), otherTenant.getId());
        assertEquals(1, optionLive(ids, "Bearer agent").size()); assertEquals(2, optionLive(ids, null).size());
        assertEquals(List.of(), optionLive(List.of(), null));
        assertThrows(BusinessException.class, () -> controller.liveOptionOrders(Map.of(), null));
        assertThrows(BusinessException.class, () -> controller.liveOptionOrders(Map.of("ids", Collections.nCopies(101, 1L)), null));
        for (Object invalid : List.of(0, -1, "1.5", "bad")) assertThrows(BusinessException.class, () -> controller.liveOptionOrders(Map.of("ids", List.of(invalid)), null));
        java.lang.reflect.Method method = AdminOrderController.class.getMethod("liveOptionOrders", Map.class, String.class);
        assertEquals("orders", method.getAnnotation(com.gtcfesk.exchange.config.AdminPermission.class).menu());
        assertTrue(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
        assertEquals("no-store", controller.liveOptionOrders(Map.of("ids", List.of()), null).getHeaders().getCacheControl());
        assertEquals("orders", com.gtcfesk.exchange.simulation.AdminReadRoutes.permission("POST", "/api/admin/orders/option/live"));
    }

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
