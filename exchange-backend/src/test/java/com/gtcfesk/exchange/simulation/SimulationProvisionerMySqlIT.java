package com.gtcfesk.exchange.simulation;

import com.gtcfesk.exchange.tenant.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Actual MySQL transactions in a newly independently restored owned money clone.
 * Component seeding only; dedicated DEMO startup/identity/UI acceptance is separate. */
class SimulationProvisionerMySqlIT {
    JdbcTemplate jdbc; SimulationProvisioner service; TenantContext.Scope scope; long user;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source=DedicatedMysqlFixture.fromProperty("stage2.mysql.fixture");
        jdbc=new JdbcTemplate(source);
        assertTrue(jdbc.queryForObject("SELECT VERSION()",String.class).startsWith("5.7."));
        assertTrue(jdbc.queryForObject("SELECT DATABASE()",String.class).startsWith("mt705_probe_"));
        SimulationEnvironment environment=new SimulationEnvironment(source);
        ReflectionTestUtils.setField(environment,"enabled",true);
        service=new SimulationProvisioner(environment,mock(SimulationGateway.class),jdbc,new DataSourceTransactionManager(source),mock(ForexQuoteMarketService.class));
        user=jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1000 FROM user_account",Long.class);
        scope=TenantContext.open(1L);
    }
    @AfterEach void clear(){scope.close();TenantContext.clear();}
    void parallel() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> calls=new ArrayList<>();
            for(int i=0;i<32;i++)calls.add(pool.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(1L)){service.user(user);}finally{TenantContext.clear();}}));
            for(Future<?> call:calls)call.get(30,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
    }
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=1 AND user_id=?",Long.class,user);}
    @Test void concurrentFirstAndReturningRequestsCannotDeadlockOrRemint() throws Exception {
        parallel();assertEquals(1,count("simulation_seed"));assertEquals(3,count("asset_account"));
        assertEquals(0,new BigDecimal("300000").compareTo(jdbc.queryForObject("SELECT SUM(available) FROM asset_account WHERE tenant_id=1 AND user_id=?",BigDecimal.class,user)));
        jdbc.update("UPDATE asset_account SET available=42 WHERE tenant_id=1 AND user_id=? AND coin='FUND'",user);
        parallel();assertEquals(1,count("simulation_seed"));assertEquals(3,count("asset_account"));
        assertEquals(0,new BigDecimal("42").compareTo(jdbc.queryForObject("SELECT available FROM asset_account WHERE tenant_id=1 AND user_id=? AND coin='FUND'",BigDecimal.class,user)));
    }
    @Test void foreignTenantCollisionCannotReassignOrSeed() {
        service.user(user);
        scope.close();
        try(TenantContext.Scope ignored=TenantContext.open(999991L)){assertThrows(RuntimeException.class,()->service.user(user));}
        finally{scope=TenantContext.open(1L);}
        assertEquals(1,jdbc.queryForObject("SELECT tenant_id FROM user_account WHERE id=?",Long.class,user));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM simulation_seed WHERE tenant_id=999991",Long.class));
        assertEquals(3,count("asset_account"));
    }
    @Test void sqlFailureRollsBackUserWalletsAndSeedThenRetryIsSingle() {
        String trigger="demo_seed_failure_"+user;
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON asset_account FOR EACH ROW BEGIN IF NEW.user_id="+user+" AND NEW.coin='CONTRACT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned demo seed failure'; END IF; END");
        try {
            assertThrows(RuntimeException.class,()->service.user(user));
            assertEquals(0,count("asset_account"));assertEquals(0,count("simulation_seed"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE id=?",Long.class,user));
        } finally {jdbc.execute("DROP TRIGGER "+trigger);}
        service.user(user);service.user(user);assertEquals(3,count("asset_account"));assertEquals(1,count("simulation_seed"));
    }
}
