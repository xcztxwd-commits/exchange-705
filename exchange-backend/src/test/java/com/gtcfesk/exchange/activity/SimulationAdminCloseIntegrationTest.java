package com.gtcfesk.exchange.activity;

import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.simulation.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig({ActivityIntegrationTest.Config.class,SimulationAdminCloseIntegrationTest.Config.class})
@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class SimulationAdminCloseIntegrationTest {
    @Configuration @Import({ControlledExitService.class,SimulationOrderCloseBoundary.class,
            com.gtcfesk.exchange.user.FinancialService.class,com.gtcfesk.exchange.user.LoanService.class,
            com.gtcfesk.exchange.user.LoanPersonalInfoService.class})
    static class Config {
        @Bean AdminPermissionService permissions(){return mock(AdminPermissionService.class);}
        @Bean SimulationEnvironment environment(){SimulationEnvironment env=mock(SimulationEnvironment.class);when(env.enabled()).thenReturn(true);return env;}
    }
    @Autowired SimulationOrderCloseBoundary boundary;@Autowired ContractOrderRepository orders;@Autowired OptionOrderRepository options;
    @Autowired AssetAccountRepository assets;@Autowired UserAccountRepository users;@Autowired AdminUserRepository admins;
    @Autowired BalanceAdjustmentRepository receipts;@Autowired OperationLogRepository logs;@Autowired ObjectMapper mapper;@Autowired DataSource dataSource;
    Long user,id;String symbol;
    @BeforeEach void setup(){
        ReflectionTestUtils.setField(boundary,"key","12345678901234567890123456789012");
        UserAccount owner=new UserAccount();owner.setEmail(UUID.randomUUID()+"@demo-close.test");owner.setPasswordHash("SIMULATION-NO-LOCAL-LOGIN");user=users.saveAndFlush(owner).getId();
        AssetAccount wallet=new AssetAccount();wallet.setUserId(user);wallet.setCoin("CONTRACT");wallet.setAvailable(new BigDecimal("89"));wallet.setFrozen(new BigDecimal("11"));assets.saveAndFlush(wallet);
        symbol="demo-close-"+user;ContractOrder order=new ContractOrder();order.setUserId(user);order.setSymbol(symbol);order.setSide("BUY");order.setType("MARKET");
        order.setStatus("OPEN");order.setQuantity(BigDecimal.ONE);order.setLotSize(BigDecimal.ONE);order.setLeverage(BigDecimal.TEN);order.setOpenPrice(new BigDecimal("100"));
        order.setMargin(BigDecimal.TEN);order.setFee(BigDecimal.ONE);order.setFundingSource("CONTRACT");id=orders.saveAndFlush(order).getId();
        when(ActivityIntegrationTest.Config.quotes.freshPrice(symbol)).thenReturn(new BigDecimal("110"));
    }
    Map<String,Object> command(String request){Map<String,Object> body=new LinkedHashMap<>();body.put("kind","contract");body.put("orderId",id);body.put("actorType","ADMIN");body.put("actorId",99191L);body.put("actorEmail","canonical-admin@example.test");body.put("requestId",request);body.put("reason","验证模拟平仓");return body;}
    MockHttpServletResponse close(Map<String,Object> body,long tenant) throws Exception {
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/simulation/order-close");request.addHeader("X-Simulation-Inspection-Key","12345678901234567890123456789012");request.addHeader("X-Simulation-Tenant-Id",String.valueOf(tenant));request.setContent(mapper.writeValueAsBytes(body));
        MockHttpServletResponse response=new MockHttpServletResponse();boundary.doFilter(request,response,new MockFilterChain());assertNull(SimulationOrderCloseBoundary.current());return response;
    }
    void balance(String coin,String available,String frozen){AssetAccount wallet=assets.findByTenantIdAndUserIdAndCoin(1L,user,coin).orElseThrow();assertEquals(0,new BigDecimal(available).compareTo(wallet.getAvailable()));assertEquals(0,new BigDecimal(frozen).compareTo(wallet.getFrozen()));}
    @Test void originalSettlementAndReceiptReplayWorkWithoutCreatingDemoAdministrators() throws Exception {
        Map<String,Object> body=command("close-replay-0123456789");assertEquals(200,close(body,1).getStatus());assertEquals("CLOSED",orders.findByTenantIdAndId(1L,id).orElseThrow().getStatus());balance("CONTRACT","109","0");
        assertEquals(200,close(body,1).getStatus());balance("CONTRACT","109","0");
        BalanceAdjustment receipt=receipts.findByTenantIdAndRequestKey(1L,(String)body.get("requestId")).orElseThrow();assertEquals(99191L,receipt.getActorId());assertTrue(receipt.getChanges().contains("\"accountMode\":\"DEMO\""));assertEquals(0,new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM admin_user",Integer.class));
        body.put("reason","changed command");assertEquals(409,close(body,1).getStatus());balance("CONTRACT","109","0");
    }
    @Test void concurrentRetriesCannotReturnPrincipalOrProfitTwice() throws Exception {
        Map<String,Object> body=command("close-parallel-0123456789");ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call=()->{try(TenantContext.Scope ignored=TenantContext.open(1L)){return close(body,1).getStatus();}};
            Future<Integer> one=pool.submit(call),two=pool.submit(call);assertEquals(200,one.get(25,TimeUnit.SECONDS));assertEquals(200,two.get(25,TimeUnit.SECONDS));balance("CONTRACT","109","0");
        } finally {pool.shutdownNow();}
    }
    @Test void staleQuotesAndWrongTenantCannotChangeFundsOrOrders() throws Exception {
        Map<String,Object> body=command("close-reject-0123456789");assertEquals(403,close(body,2).getStatus());balance("CONTRACT","89","11");
        when(ActivityIntegrationTest.Config.quotes.freshPrice(symbol)).thenReturn(null);assertEquals(400,close(body,1).getStatus());balance("CONTRACT","89","11");
        assertEquals("OPEN",orders.findByTenantIdAndId(1L,id).orElseThrow().getStatus());assertFalse(receipts.findByTenantIdAndRequestKey(1L,(String)body.get("requestId")).isPresent());
    }
    @Test void receiptFailureRollsBackTheEntireSettlement() throws Exception {
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);jdbc.execute("ALTER TABLE balance_adjustment ADD CONSTRAINT reject_demo_close_receipt CHECK(request_key <> 'close-rollback-0123456789')");
        try {assertEquals(500,close(command("close-rollback-0123456789"),1).getStatus());balance("CONTRACT","89","11");assertEquals("OPEN",orders.findByTenantIdAndId(1L,id).orElseThrow().getStatus());}
        finally {jdbc.execute("ALTER TABLE balance_adjustment DROP CONSTRAINT reject_demo_close_receipt");}
    }
    @Test void optionCloseUsesTheSameIsolatedCashSettlement() throws Exception {
        AssetAccount wallet=new AssetAccount();wallet.setUserId(user);wallet.setCoin("OPTION");wallet.setAvailable(BigDecimal.ZERO);wallet.setFrozen(new BigDecimal("100"));assets.saveAndFlush(wallet);
        OptionOrder order=new OptionOrder();order.setUserId(user);order.setSymbol(symbol);order.setDirection("UP");order.setAmount(new BigDecimal("100"));order.setOpenPrice(new BigDecimal("100"));order.setDuration(60);order.setStatus("TRADING");order.setFundingSource("OPTION");order=options.saveAndFlush(order);
        Map<String,Object> body=command("option-close-0123456789");body.put("kind","option");body.put("orderId",order.getId());assertEquals(200,close(body,1).getStatus());
        assertEquals("CLOSED",options.findByTenantIdAndId(1L,order.getId()).orElseThrow().getStatus());balance("OPTION","180","0");balance("CONTRACT","89","11");assertEquals(200,close(body,1).getStatus());balance("OPTION","180","0");
    }
}
