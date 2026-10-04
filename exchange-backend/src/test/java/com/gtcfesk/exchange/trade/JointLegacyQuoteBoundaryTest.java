package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No database fixtures: unsupported new-mode entry points reject before any dependency or funding DML. */
class JointLegacyQuoteBoundaryTest {
    @Test void newAuthorityModeDoesNotFallBackToLegacyMoneyRoutes() {
        ContractOrderService contract=mock(ContractOrderService.class,CALLS_REAL_METHODS);
        OptionOrderService option=mock(OptionOrderService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(contract,"s3SchedulingEnabled",true);
        ReflectionTestUtils.setField(option,"s3SchedulingEnabled",true);
        java.util.List<org.junit.jupiter.api.function.Executable> routes=java.util.List.of(
            ()->contract.createOrder(null,null), ()->contract.closeOrder(null,null,null),
            ()->contract.adminCloseOrder(null,null), ()->contract.matchPendingLimitOrders(),
            contract::checkAndAutoCloseSnapshotOrders, ()->contract.checkAndAutoCloseOrders(null,null),
            ()->contract.checkAndForceCloseOrders(null), ()->option.createOrder(null,null),
            ()->option.closeOrder(null,null,null));
        for(org.junit.jupiter.api.function.Executable route:routes)
            assertTrue(assertThrows(BusinessException.class,route).getMessage().contains("S3_QUOTE_REJECTED"));
    }
}
