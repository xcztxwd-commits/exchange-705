package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.admin.AdminOrderController;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.trade.ContractOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(DepositOrderServiceTest.Config.class)
@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class OrderSoftDeleteTest {
    static { ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN); }
    @Autowired ContractOrderRepository contracts;
    @Autowired OptionOrderRepository options;
    @Autowired UserAccountRepository users;
    @Autowired AssetAccountRepository assets;
    @Autowired PlatformTransactionManager manager;

    AdminOrderController controller() {
        return new AdminOrderController(contracts, options, mock(ContractOrderService.class), users, assets, mock(JwtUtil.class));
    }
    UserAccount user() {
        UserAccount u = new UserAccount(); u.setEmail(UUID.randomUUID()+"@test.invalid"); u.setPasswordHash("unused");
        return users.saveAndFlush(u);
    }
    ContractOrder contract(long user, String status) {
        ContractOrder o = new ContractOrder(); o.setUserId(user); o.setSymbol("BTCUSDT"); o.setSide("BUY");
        o.setType("MARKET"); o.setQuantity(BigDecimal.ONE); o.setStatus(status);
        return contracts.saveAndFlush(o);
    }
    OptionOrder option(long user, String status) {
        OptionOrder o = new OptionOrder(); o.setUserId(user); o.setSymbol("BTCUSDT"); o.setDirection("UP");
        o.setAmount(BigDecimal.TEN); o.setStatus(status); return options.saveAndFlush(o);
    }
    Map<String,Object> params(long user, String deletion) {
        Map<String,Object> p=new HashMap<>(); p.put("userId", String.valueOf(user)); p.put("deletion", deletion); p.put("size", 1); return p;
    }
    @Test void contractDeleteHideFilterRestoreAndNoMoneyMutation() {
        UserAccount u=user(); ContractOrder o=contract(u.getId(), "CLOSED"); contract(u.getId(), "CANCELLED");
        AdminOrderController c=controller(); TransactionTemplate tx=new TransactionTemplate(manager);
        AssetAccount account=new AssetAccount(); account.setUserId(u.getId()); account.setCoin("CONTRACT");
        account.setAvailable(new BigDecimal("123")); account.setFrozen(new BigDecimal("7")); assets.saveAndFlush(account);
        java.time.LocalDateTime closeTime=contracts.findByTenantIdAndId(1L, o.getId()).get().getCloseTime();
        long count=contracts.countByTenantId(1L);
        tx.execute(s -> c.softDeleteContractOrder(o.getId(), new UsernamePasswordAuthenticationToken("admin-test", "unused")));
        tx.execute(s -> c.softDeleteContractOrder(o.getId(), new UsernamePasswordAuthenticationToken("other", "unused")));
        assertEquals(count, contracts.countByTenantId(1L));
        assertEquals("CLOSED", contracts.findByTenantIdAndId(1L, o.getId()).get().getStatus());
        assertEquals("admin-test", contracts.findByTenantIdAndId(1L, o.getId()).get().getDeletedBy());
        assertEquals(1, contracts.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(1L, u.getId()).size());
        assertTrue(contracts.findByTenantIdAndUserIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(1L, u.getId(), "CLOSED").isEmpty());
        Map<?,?> all=(Map<?,?>)c.queryContractOrders(params(u.getId(), ""), null).getBody();
        assertEquals(2L, all.get("total")); assertEquals(1, ((List<?>)all.get("list")).size());
        Map<?,?> deleted=(Map<?,?>)c.queryContractOrders(params(u.getId(), "deleted"), null).getBody();
        assertEquals(1L, deleted.get("total")); assertEquals(true, ((Map<?,?>)((List<?>)deleted.get("list")).get(0)).get("deleted"));
        assertEquals(1L, ((Map<?,?>)c.queryContractOrders(params(u.getId(), "active"), null).getBody()).get("total"));
        tx.execute(s -> c.restoreContractOrder(o.getId())); tx.execute(s -> c.restoreContractOrder(o.getId()));
        assertEquals(2, contracts.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(1L, u.getId()).size());
        assertFalse(contracts.findByTenantIdAndId(1L, o.getId()).get().isDeleted());
        assertEquals(closeTime, contracts.findByTenantIdAndId(1L, o.getId()).get().getCloseTime());
        assertEquals(0, new BigDecimal("123").compareTo(assets.findByTenantIdAndUserIdAndCoin(1L, u.getId(), "CONTRACT").get().getAvailable()));
        assertEquals(0, new BigDecimal("7").compareTo(assets.findByTenantIdAndUserIdAndCoin(1L, u.getId(), "CONTRACT").get().getFrozen()));
        assertThrows(BusinessException.class, () -> c.softDeleteContractOrder(contract(u.getId(), "OPEN").getId(), new UsernamePasswordAuthenticationToken("admin", "unused")));
        assertThrows(BusinessException.class, () -> c.softDeleteContractOrder(contract(u.getId(), "PENDING").getId(), new UsernamePasswordAuthenticationToken("admin", "unused")));
    }
    @Test void agentScopeAndInvalidFilter() throws Exception {
        UserAccount agent=user(), own=user(), outsider=user(); own.setParentUserId(agent.getId()); users.saveAndFlush(own);
        contract(own.getId(), "CLOSED"); contract(outsider.getId(), "CLOSED");
        JwtUtil jwt=mock(JwtUtil.class); when(jwt.parse("scope")).thenReturn(io.jsonwebtoken.Jwts.claims().setSubject("agent-"+agent.getId()));
        AdminOrderController c=new AdminOrderController(contracts, options, mock(ContractOrderService.class), users, assets, jwt);
        assertEquals(0L, ((Map<?,?>)c.queryContractOrders(params(outsider.getId(), ""), "Bearer scope").getBody()).get("total"));
        assertEquals(1L, ((Map<?,?>)c.queryContractOrders(params(own.getId(), ""), "Bearer scope").getBody()).get("total"));
        assertThrows(BusinessException.class, () -> c.queryContractOrders(params(own.getId(), "bad"), null));
    }
    @Test void agentsCannotDeleteOrRestoreEitherOrderType() throws Exception {
        java.lang.reflect.Constructor<?> constructor=com.gtcfesk.exchange.config.BackendAccess.class.getConstructors()[0];
        com.gtcfesk.exchange.config.BackendAccess access=(com.gtcfesk.exchange.config.BackendAccess)constructor.newInstance(new Object[constructor.getParameterCount()]);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("agent-1", "unused", Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_AGENT"))));
        try {
            for (String method: Arrays.asList("softDeleteContractOrder", "softDeleteOptionOrder", "restoreContractOrder", "restoreOptionOrder")) {
                java.lang.reflect.Method target=method.startsWith("softDelete")
                    ? AdminOrderController.class.getMethod(method, Long.class, org.springframework.security.core.Authentication.class)
                    : AdminOrderController.class.getMethod(method, Long.class);
                org.springframework.mock.web.MockHttpServletRequest request=new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/admin/orders");
                assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> access.preHandle(request,
                    new org.springframework.mock.web.MockHttpServletResponse(), new org.springframework.web.method.HandlerMethod(controller(), target)));
            }
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    @Test void staleVersionCannotOverwriteDeletion() {
        UserAccount u=user(); ContractOrder o=contract(u.getId(), "CLOSED"); TransactionTemplate tx=new TransactionTemplate(manager);
        assertEquals(1, (int)tx.execute(s -> contracts.updateDeletion(o.getId(), o.getRowVersion(), java.time.LocalDateTime.now(), "admin")));
        assertEquals(0, (int)tx.execute(s -> contracts.updateDeletion(o.getId(), o.getRowVersion(), null, null)));
        assertTrue(contracts.findByTenantIdAndId(1L, o.getId()).get().isDeleted());
    }
    @Test void optionDeleteRestoreAndActiveProtection() {
        UserAccount u=user(); OptionOrder o=option(u.getId(), "CLOSED"); AdminOrderController c=controller();
        TransactionTemplate tx=new TransactionTemplate(manager); long count=options.countByTenantId(1L);
        tx.execute(s -> c.softDeleteOptionOrder(o.getId(), new UsernamePasswordAuthenticationToken("admin", "unused")));
        assertEquals(count, options.countByTenantId(1L)); assertEquals("CLOSED", options.findByTenantIdAndId(1L, o.getId()).get().getStatus());
        assertTrue(options.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(1L, u.getId()).isEmpty());
        assertTrue(options.findByTenantIdAndUserIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(1L, u.getId(), "CLOSED").isEmpty());
        assertEquals(1L, ((Map<?,?>)c.queryOptionOrders(params(u.getId(), "deleted"), null).getBody()).get("total"));
        tx.execute(s -> c.restoreOptionOrder(o.getId()));
        assertEquals(1, options.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(1L, u.getId()).size());
        assertEquals(0, BigDecimal.TEN.compareTo(options.findByTenantIdAndId(1L, o.getId()).get().getAmount()));
        assertThrows(BusinessException.class, () -> c.softDeleteOptionOrder(option(u.getId(), "TRADING").getId(), new UsernamePasswordAuthenticationToken("admin", "unused")));
    }
}
