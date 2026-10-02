package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.admin.AdminOrderController;
import com.gtcfesk.exchange.admin.ManualOrderController;
import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimpleManualOrderAdminTest {
    @Test void unboundManualOrderCanBeListedWithoutNullUserLookup() {
        try(TenantContext.Scope ignored=TenantContext.open(1L)) {
            ContractOrderRepository orders=mock(ContractOrderRepository.class);UserAccountRepository users=mock(UserAccountRepository.class);
            AdminOrderController controller=new AdminOrderController(orders,mock(OptionOrderRepository.class),mock(ContractOrderService.class),users,mock(AssetAccountRepository.class),null);
            ContractOrder order=new ContractOrder();order.setId(7L);order.setOrderSource("MANUAL_TEST");order.setStatus("CLOSED");
            when(orders.findAllByTenantId(eq(1L),any(),any(Pageable.class))).thenReturn(new PageImpl<>(Collections.singletonList(order)));
            Map<?,?> body=(Map<?,?>)controller.queryContractOrders(Collections.emptyMap(),null).getBody();
            Map<?,?> row=(Map<?,?>)((List<?>)body.get("list")).get(0);
            assertNull(row.get("userId"));assertEquals("UNBOUND",row.get("bindingStatus"));assertEquals("MANUAL_TEST",row.get("orderSource"));verifyNoInteractions(users);
        }
    }
    @Test void simpleAndBindingRoutesRetainManualOrderPermission() {
        int count=0;
        for(java.lang.reflect.Method method:ManualOrderController.class.getDeclaredMethods()) {
            if(!Arrays.asList("simpleGenerate","simplePreview","simpleCreate","bindPreview","bind","chart").contains(method.getName()))continue;
            AdminPermission permission=method.getAnnotation(AdminPermission.class);assertNotNull(permission);assertEquals("orders",permission.menu());assertEquals("manual_order",permission.action());count++;
        }
        assertEquals(6,count);
    }
}
