package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.ContractOrderRepository;
import com.gtcfesk.exchange.repository.OptionOrderRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.trade.ContractOrderService;
import com.gtcfesk.exchange.common.JwtUtil;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AdminOrderQuantityProjectionTest {
    @Test void netProfitUsesExactStoredAmountsAndOriginalSettlementProtocol() {
        AdminOrderController controller = new AdminOrderController(mock(ContractOrderRepository.class),
                mock(OptionOrderRepository.class), mock(ContractOrderService.class),
                mock(UserAccountRepository.class), mock(AssetAccountRepository.class), mock(JwtUtil.class));
        ContractOrder order = new ContractOrder();
        order.setLotSize(new BigDecimal("1000")); order.setStatus("CLOSED");
        order.setFee(new BigDecimal("123.45"));
        for (String[] amounts : new String[][] {
                {"4828.58", "4705.13"}, {"-10.11", "-133.56"}, {"0", "-123.45"},
                {"123.45", "0.00"}, {"9007199254740993.01", "9007199254740869.56"}}) {
            order.setProfit(new BigDecimal(amounts[0]));
            Map<String,Object> row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
            assertEquals(new BigDecimal(amounts[0]), row.get("profit"));
            assertEquals(order.getFee(), row.get("fee"));
            assertEquals(0, new BigDecimal(amounts[1]).compareTo((BigDecimal) row.get("netProfit")));
        }
        order.setOrderSource("MANUAL_TEST"); order.setUserId(null); order.setDeletedAt(java.time.LocalDateTime.now());
        order.setProfit(new BigDecimal("2.50")); order.setFee(new BigDecimal("5.00"));
        Map<String,Object> row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
        assertEquals(new BigDecimal("-2.50"), row.get("netProfit")); assertEquals(true, row.get("deleted"));
        order.setFee(BigDecimal.ZERO);
        row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
        assertEquals(new BigDecimal("2.50"), row.get("netProfit"));
        order.setFee(null);
        row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
        assertTrue(row.containsKey("netProfit")); assertNull(row.get("netProfit")); assertNull(row.get("fee"));
        order.setFee(new BigDecimal("5.00")); order.setProfit(null);
        row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order); assertNull(row.get("netProfit"));
        order.setProfit(new BigDecimal("2.50")); order.setStatus("OPEN");
        row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
        assertEquals(new BigDecimal("-2.50"), row.get("netProfit"));
        for (String status : new String[] {"PENDING", "CANCELLED"}) {
            order.setStatus(status); order.setProfit(BigDecimal.ZERO);
            row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
            assertEquals(BigDecimal.ZERO, row.get("netProfit")); assertEquals(new BigDecimal("5.00"), row.get("fee"));
        }
        order.setLotSize(null); order.setStatus("CLOSED"); order.setProfit(new BigDecimal("2.50"));
        row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
        assertEquals(new BigDecimal("2.50"), row.get("netProfit"));
        order.setFee(null);
        row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
        assertEquals(new BigDecimal("2.50"), row.get("netProfit"));
    }

    @Test void nativeAndLegacyRowsUseTheirOwnSnapshotsWithoutLosingDeleteFields() {
        AdminOrderController controller = new AdminOrderController(mock(ContractOrderRepository.class),
                mock(OptionOrderRepository.class), mock(ContractOrderService.class),
                mock(UserAccountRepository.class), mock(AssetAccountRepository.class), mock(JwtUtil.class));
        ContractOrder order = new ContractOrder();
        order.setId(72L); order.setQuantity(new BigDecimal("0.001"));
        order.setQuantityUnitType("BASE_ASSET"); order.setQuantityAsset("BTC"); order.setSpecVersion(1L);
        Map<String,Object> row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
        assertEquals("BASE_ASSET", row.get("quantityUnitType"));
        assertEquals("BTC", row.get("quantityAsset"));
        assertEquals(1L, row.get("specVersion"));
        assertEquals(new BigDecimal("0.001"), row.get("quantity"));
        assertEquals(72L, row.get("id"));
        assertEquals(false, row.get("deleted"));
        order.setQuantityUnitType("SHARE"); order.setQuantityAsset("AAPL");
        row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
        assertEquals("SHARE", row.get("quantityUnitType"));
        assertEquals("AAPL", row.get("quantityAsset"));
        order.setDeletedAt(java.time.LocalDateTime.now());
        row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
        assertEquals(true, row.get("deleted"));
        assertEquals(order.getDeletedAt(), row.get("deletedAt"));
        order.setQuantityUnitType(null); order.setQuantityAsset(null); order.setSpecVersion(null);
        row = ReflectionTestUtils.invokeMethod(controller, "convertContractOrderToMap", order);
        assertTrue(row.containsKey("quantityUnitType")); assertNull(row.get("quantityUnitType"));
        assertTrue(row.containsKey("quantityAsset")); assertNull(row.get("quantityAsset"));
        assertEquals(new BigDecimal("0.001"), row.get("quantity"));
    }
}
