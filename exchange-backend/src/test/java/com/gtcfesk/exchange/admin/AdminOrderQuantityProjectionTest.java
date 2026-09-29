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
