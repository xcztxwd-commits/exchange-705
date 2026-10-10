package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.*;
import com.gtcfesk.exchange.user.FinancialService;
import com.gtcfesk.exchange.user.FinancialYieldService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.PersistenceContext;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real JPA writes, rollback and physical commits on H2 or the explicitly identified S3 disposable MySQL/RR schema. No application boot/RPC/real money. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringJUnitConfig(FinancialAccrualTransactionTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class FinancialAccrualTransactionTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    @EnableJpaRepositories(basePackages="com.gtcfesk.exchange", repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
    @Import({FinancialService.class, FinancialYieldService.class, ControlAuditService.class})
    static class Config {
        @Bean(destroyMethod="close") ObservedDataSource dataSource() {
            HikariDataSource pool = new HikariDataSource();
            pool.setMaximumPoolSize(2); pool.setMinimumIdle(0); pool.setConnectionTimeout(15000);
            String mysqlPort=System.getProperty("activity.test.mysqlPort");
            if(mysqlPort==null) {
                pool.setJdbcUrl("jdbc:h2:mem:s3_financial_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000");
                pool.setUsername("sa"); pool.setPassword("");
            } else {
                // This create-drop JPA schema suite is not a migrated DedicatedMysqlFixture acceptance test.
                // Root must verify the disposable S3 resource's full container/label/volume identity first.
                if(!"33419".equals(mysqlPort)) throw new IllegalArgumentException("Only this S3 disposable JPA fixture port 33419 is permitted");
                String expectedUuid=System.getProperty("financial.test.mysqlUuid");
                String password=System.getenv("ACTIVITY_TEST_MYSQL_PASSWORD");
                if(expectedUuid==null || !expectedUuid.matches("[a-f0-9-]{36}") || password==null || password.isEmpty())
                    throw new IllegalArgumentException("Explicit S3 MySQL UUID and private test password are required");
                pool.setJdbcUrl("jdbc:mysql://127.0.0.1:33419/activity_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8");
                pool.setUsername("root"); pool.setPassword(password);
                try(Connection connection=pool.getConnection(); java.sql.Statement statement=connection.createStatement();
                    java.sql.ResultSet identity=statement.executeQuery("SELECT @@server_uuid,VERSION(),DATABASE(),@@tx_isolation")) {
                    if(!identity.next() || !expectedUuid.equals(identity.getString(1)) || !identity.getString(2).startsWith("5.7.")
                            || !"activity_test".equals(identity.getString(3)) || !"REPEATABLE-READ".equals(identity.getString(4)))
                        throw new IllegalArgumentException("S3 disposable JPA fixture identity/isolation changed; no schema writes permitted");
                } catch(java.sql.SQLException | RuntimeException failure) { pool.close(); throw new IllegalStateException("S3 fixture identity check failed",failure); }
            }
            return new ObservedDataSource(pool);
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source); factory.setPackagesToScan("com.gtcfesk.exchange");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties properties = new Properties();
            properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
            properties.setProperty("hibernate.physical_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
            properties.setProperty("hibernate.session_factory.statement_inspector", SqlObserver.class.getName());
            factory.setJpaProperties(properties); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean TenantReadinessService readiness() { return mock(TenantReadinessService.class); }
        @Bean com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound() { return mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class); }
        @Bean TenantPolicyService policy() { return mock(TenantPolicyService.class); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean OperationalIssueService issues() { return mock(OperationalIssueService.class); }
        @Bean TenantJobRunner jobs(JdbcTemplate jdbc, PlatformTransactionManager manager) { return new TenantJobRunner(jdbc,manager); }
    }

    static class ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
        final HikariDataSource pool;
        final boolean mysql;
        final AtomicInteger commits = new AtomicInteger(), rollbacks = new AtomicInteger(), requestDuplicates = new AtomicInteger();
        static final ThreadLocal<CyclicBarrier> purchaseInserts=new ThreadLocal<>();
        ObservedDataSource(HikariDataSource pool) { super(pool); this.pool=pool; this.mysql=pool.getJdbcUrl().startsWith("jdbc:mysql:"); }
        @Override public Connection getConnection() throws java.sql.SQLException {
            Connection connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                if ("commit".equals(method.getName())) commits.incrementAndGet();
                if ("rollback".equals(method.getName())) rollbacks.incrementAndGet();
                try {
                    Object result=method.invoke(connection,args);
                    if("prepareStatement".equals(method.getName()) && args!=null && args.length>0
                            && args[0] instanceof String && ((String)args[0]).toLowerCase(Locale.ROOT).startsWith("insert into financial_order ")) {
                        java.sql.PreparedStatement statement=(java.sql.PreparedStatement)result;
                        return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(prepared,operation,values) -> {
                            if("executeUpdate".equals(operation.getName()) && purchaseInserts.get()!=null) await(purchaseInserts.get());
                            try { return operation.invoke(statement,values); }
                            catch(InvocationTargetException failure) {
                                Throwable cause=failure.getCause();
                                if(cause instanceof java.sql.SQLException && ((java.sql.SQLException)cause).getErrorCode()==1062) requestDuplicates.incrementAndGet();
                                throw cause;
                            }
                        });
                    }
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        @Override public void close() { pool.close(); }
    }

    public static class SqlObserver implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<List<Boolean>> scans = ThreadLocal.withInitial(ArrayList::new);
        static final ThreadLocal<List<String>> statements=ThreadLocal.withInitial(ArrayList::new);
        @Override public String inspect(String sql) {
            String lower = sql.toLowerCase(Locale.ROOT);
            statements.get().add(lower);
            if (lower.startsWith("select ") && lower.contains(" from financial_order ")
                    && lower.contains(".id>") && !lower.contains("for update"))
                scans.get().add(TransactionSynchronizationManager.isActualTransactionActive());
            return sql;
        }
    }

    @Autowired FinancialService financial;
    @Autowired FinancialYieldService yields;
    @SpyBean UserAccountRepository users;
    @SpyBean AssetAccountRepository assets;
    @SpyBean FinancialOrderRepository orders;
    @SpyBean FinancialYieldRecordRepository receipts;
    @Autowired FinancialProductRepository products;
    @Autowired ObservedDataSource source;
    @Autowired PlatformTransactionManager manager;
    @SpyBean TenantJobRunner jobs;
    @PersistenceContext EntityManager entityManager;
    JdbcTemplate database;
    TenantContext.Scope scope;
    long tenant, actor, user;
    FinancialProduct product;

    @BeforeEach void setup() {
        tenant = Math.abs(UUID.randomUUID().getMostSignificantBits()%100000000L)+1000L;
        scope=TenantContext.open(tenant); database=new JdbcTemplate(source);
        tx(() -> {
            ControlAdmin admin=new ControlAdmin(); admin.setAccount(UUID.randomUUID().toString()); admin.setPasswordHash("not-a-login");
            entityManager.persist(admin); actor=admin.getId();
            user=newUser();
            product=new FinancialProduct(); product.setName("S3 synthetic finance"); product.setDailyYieldRate(BigDecimal.ONE);
            product.setRentalFee(BigDecimal.ZERO); product.setMinPurchase(BigDecimal.ONE); product.setMaxPurchase(new BigDecimal("10000"));
            product.setTermDays(7); product.setPenaltyRate(new BigDecimal("10")); products.save(product);
        });
        control(); source.commits.set(0); source.rollbacks.set(0); source.requestDuplicates.set(0); SqlObserver.scans.get().clear(); SqlObserver.statements.get().clear();
        clearInvocations(users,assets,orders,receipts); reset(jobs);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); scope.close(); TenantContext.clear(); SqlObserver.scans.remove(); SqlObserver.statements.remove(); }
    void control() {
        UsernamePasswordAuthenticationToken authentication=new UsernamePasswordAuthenticationToken("-"+actor,null,
                Arrays.asList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),new SimpleGrantedAuthority("ROLE_CONTROL_ACCESS")));
        authentication.setDetails(new ControlIdentity(actor,tenant,"s3-financial-test"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
    void tx(Runnable action) { new TransactionTemplate(manager).execute(status -> { action.run(); return null; }); }
    long newUser() {
        UserAccount owner=new UserAccount(); owner.setEmail(UUID.randomUUID()+"@example.invalid"); owner.setPasswordHash("not-a-login");
        users.save(owner);
        AssetAccount account=new AssetAccount(); account.setUserId(owner.getId()); account.setCoin("FUND");
        account.setAvailable(new BigDecimal("1000")); account.setFrozen(new BigDecimal("100")); assets.save(account);
        return owner.getId();
    }
    FinancialOrder order(long owner, LocalDate first, int term) {
        FinancialOrder order=new FinancialOrder(); order.setUserId(owner); order.setProductId(product.getId()); order.setProductName(product.getName());
        order.setPurchaseAmount(new BigDecimal("100")); order.setDailyYieldRate(BigDecimal.ONE); order.setDailyYield(BigDecimal.ONE);
        order.setTotalYield(BigDecimal.valueOf(term)); order.setTermDays(term); order.setPenaltyRate(new BigDecimal("10"));
        order.setPurchaseTime(first.atStartOfDay()); order.setEndTime(first.plusDays(term).atStartOfDay());
        tx(() -> orders.save(order)); return order;
    }
    FinancialOrder current(FinancialOrder order) { return orders.findByTenantIdAndId(tenant,order.getId()).get(); }
    long count(FinancialOrder order) { return database.queryForObject("SELECT COUNT(*) FROM financial_yield_record WHERE tenant_id=? AND order_id=?",Long.class,tenant,order.getId()); }
    long audits(String action) { return database.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND actor_id=? AND action=?",Long.class,tenant,actor,action); }
    long receipt(FinancialOrder order) { return database.queryForObject("SELECT id FROM financial_yield_record WHERE tenant_id=? AND order_id=?",Long.class,tenant,order.getId()); }
    void money(long owner,String available,String frozen) {
        Map<String,Object> account=database.queryForMap("SELECT available,frozen FROM asset_account WHERE tenant_id=? AND user_id=? AND coin='FUND'",tenant,owner);
        assertEquals(0,new BigDecimal(available).compareTo((BigDecimal)account.get("available")));
        assertEquals(0,new BigDecimal(frozen).compareTo((BigDecimal)account.get("frozen")));
    }
    void constraintFailure(String table,String check,Runnable command) {
        String name="s3_fail_"+UUID.randomUUID().toString().replace("-","");
        if(source.mysql) {
            // MySQL 5.7 ignores CHECK constraints, so fail the real write with owned temporary triggers.
            String expression=check.replaceAll("\\b(tenant_id|user_id|order_id|yield_date|id|last_accrued_date|status|available|action|object_ref)\\b","NEW.$1");
            database.execute("CREATE TRIGGER "+name+"_i BEFORE INSERT ON "+table+" FOR EACH ROW BEGIN IF NOT ("+expression+") THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='S3 controlled financial failure'; END IF; END");
            try {
                database.execute("CREATE TRIGGER "+name+"_u BEFORE UPDATE ON "+table+" FOR EACH ROW BEGIN IF NOT ("+expression+") THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='S3 controlled financial failure'; END IF; END");
                try { assertThrows(RuntimeException.class,command::run); }
                finally { database.execute("DROP TRIGGER "+name+"_u"); }
            } finally { database.execute("DROP TRIGGER "+name+"_i"); }
        } else {
            database.execute("ALTER TABLE "+table+" ADD CONSTRAINT "+name+" CHECK("+check+")");
            try { assertThrows(RuntimeException.class,command::run); }
            finally { database.execute("ALTER TABLE "+table+" DROP CONSTRAINT "+name); }
        }
    }
    void assertUnpaid(long id) {
        Map<String,Object> record=database.queryForMap("SELECT status,paid_at FROM financial_yield_record WHERE tenant_id=? AND id=?",tenant,id);
        assertEquals("PENDING",record.get("status")); assertNull(record.get("paid_at"));
    }

    @Test @Order(1) void mysqlDifferentUsersFirstRequestsHaveNoMissingKeyGapLockAcrossTwentyFourRounds() throws Exception {
        Assumptions.assumeTrue(source.mysql,"A real identified MySQL/RR fixture is required; H2 is not this proof");
        // Run first in this exclusively owned create-drop schema, so round zero is the audited empty-table case.
        assertEquals(0L,database.queryForObject("SELECT COUNT(*) FROM financial_order",Long.class));
        for(int round=0;round<24;round++) {
            long[] owners={user,0};
            final int attempt=round;
            tx(() -> { if(attempt>0)owners[0]=newUser(); owners[1]=newUser(); });
            String key="s3-first-request-"+round;
            CyclicBarrier snapshots=new CyclicBarrier(2), inserts=new CyclicBarrier(2);
            java.util.concurrent.atomic.AtomicLongArray ids=new java.util.concurrent.atomic.AtomicLongArray(2), connections=new java.util.concurrent.atomic.AtomicLongArray(2);
            Runnable[] purchases=new Runnable[2];
            for(int i=0;i<2;i++) {
                final int index=i;
                purchases[i]=() -> {
                    ObservedDataSource.purchaseInserts.set(inserts);
                    try {
                        tx(() -> {
                            assertEquals("REPEATABLE-READ",database.queryForObject("SELECT @@tx_isolation",String.class));
                            connections.set(index,database.queryForObject("SELECT CONNECTION_ID()",Long.class));
                            assertEquals(0L,database.queryForObject("SELECT COUNT(*) FROM financial_order WHERE tenant_id=? AND user_id=? AND request_key=?",Long.class,tenant,owners[index],key));
                            await(snapshots);
                            ids.set(index,financial.purchaseProduct(owners[index],product.getId(),new BigDecimal("100"),key).getId());
                            List<String> lookups=new ArrayList<>();
                            for(String sql : SqlObserver.statements.get()) if(sql.startsWith("select id from financial_order "))lookups.add(sql);
                            assertFalse(lookups.isEmpty(),"Observe the actual missing request lookup");
                            assertTrue(lookups.stream().noneMatch(sql -> sql.contains("for update") || sql.contains("lock in share mode")),"An absent request lookup must not acquire a compatible RR gap lock");
                        });
                    } finally { ObservedDataSource.purchaseInserts.remove(); }
                };
            }
            source.commits.set(0); source.rollbacks.set(0); parallel(purchases);
            assertNotEquals(connections.get(0),connections.get(1),"Two physical transactions must reach the INSERT barrier concurrently");
            assertNotEquals(ids.get(0),ids.get(1)); assertEquals(2,source.commits.get()); assertEquals(0,source.rollbacks.get());
            for(int i=0;i<2;i++) {
                // Simulate an unknown commit result: replay the exact key only after both physical commits.
                assertEquals(ids.get(i),financial.purchaseProduct(owners[i],product.getId(),new BigDecimal("100.0"),key).getId());
                money(owners[i],"900","200");
            }
            assertEquals(2L*(round+1),database.queryForObject("SELECT COUNT(*) FROM financial_order WHERE tenant_id=?",Long.class,tenant));
            assertEquals(2L*(round+1),audits("FINANCIAL_PURCHASE"));
        }
    }

    @Test void mysqlTwoEmptyRepeatableReadSnapshotsAndUnknownCommitReplayShareOneFinancialReceipt() throws Exception {
        Assumptions.assumeTrue(source.mysql,"A real identified MySQL/RR fixture is required; H2 is not this proof");
        String key="s3-same-key-empty-snapshot"; CyclicBarrier snapshots=new CyclicBarrier(2);
        java.util.concurrent.atomic.AtomicLongArray ids=new java.util.concurrent.atomic.AtomicLongArray(2), connections=new java.util.concurrent.atomic.AtomicLongArray(2);
        Runnable[] purchases=new Runnable[2];
        for(int i=0;i<2;i++) {
            final int index=i;
            purchases[i]=() -> tx(() -> {
                assertEquals("REPEATABLE-READ",database.queryForObject("SELECT @@tx_isolation",String.class));
                connections.set(index,database.queryForObject("SELECT CONNECTION_ID()",Long.class));
                assertEquals(0L,database.queryForObject("SELECT COUNT(*) FROM financial_order WHERE tenant_id=? AND user_id=? AND request_key=?",Long.class,tenant,user,key));
                await(snapshots);
                ids.set(index,financial.purchaseProduct(user,product.getId(),new BigDecimal("100"),key).getId());
            });
        }
        source.commits.set(0); source.rollbacks.set(0); parallel(purchases);
        assertNotEquals(connections.get(0),connections.get(1)); assertEquals(ids.get(0),ids.get(1));
        assertEquals(1,source.requestDuplicates.get(),"The stale empty snapshot must use the exact unique-key INSERT arbitration, not a second freeze");
        assertEquals(2,source.commits.get()); assertEquals(0,source.rollbacks.get());
        assertEquals(ids.get(0),financial.purchaseProduct(user,product.getId(),new BigDecimal("100.00"),key).getId());
        assertThrows(com.gtcfesk.exchange.common.BusinessException.class,() -> financial.purchaseProduct(user,product.getId(),new BigDecimal("101"),key));
        assertEquals(1L,database.queryForObject("SELECT COUNT(*) FROM financial_order WHERE tenant_id=?",Long.class,tenant));
        money(user,"900","200"); assertEquals(1,audits("FINANCIAL_PURCHASE"));
    }

    @ParameterizedTest @ValueSource(strings={"receipt","wallet","audit"})
    void everyKeyedPurchaseWriteFailureRollsBackUniqueReceiptFreezeAndAudit(String point) {
        String key="s3-keyed-rollback-"+point;
        String table="audit".equals(point)?"control_audit_log":"wallet".equals(point)?"asset_account":"financial_order";
        String check="audit".equals(point)?"tenant_id<>"+tenant+" OR action<>'FINANCIAL_PURCHASE'"
                :"wallet".equals(point)?"user_id<>"+user+" OR available=1000":"tenant_id<>"+tenant;
        int rollbacks=source.rollbacks.get();
        constraintFailure(table,check,() -> financial.purchaseProduct(user,product.getId(),new BigDecimal("100"),key));
        assertTrue(source.rollbacks.get()>rollbacks); money(user,"1000","100"); assertEquals(0,audits("FINANCIAL_PURCHASE"));
        assertEquals(0L,database.queryForObject("SELECT COUNT(*) FROM financial_order WHERE tenant_id=?",Long.class,tenant));
        FinancialOrder order=financial.purchaseProduct(user,product.getId(),new BigDecimal("100"),key);
        assertEquals(order.getId(),financial.purchaseProduct(user,product.getId(),new BigDecimal("100.0"),key).getId());
        money(user,"900","200"); assertEquals(1,audits("FINANCIAL_PURCHASE"));
        assertEquals(1L,database.queryForObject("SELECT COUNT(*) FROM financial_order WHERE tenant_id=?",Long.class,tenant));
    }

    static void await(CyclicBarrier barrier) {
        try { barrier.await(10,TimeUnit.SECONDS); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError("Financial SQL barrier interrupted",failure); }
        catch(BrokenBarrierException | TimeoutException failure) { throw new AssertionError("Both financial transactions must reach the SQL barrier",failure); }
    }
    void assertSqlOrder(String... prefixes) {
        int previous=-1;
        for(String prefix : prefixes) {
            int found=-1;
            for(int i=previous+1;i<SqlObserver.statements.get().size();i++) if(SqlObserver.statements.get().get(i).startsWith(prefix)) {found=i;break;}
            assertTrue(found>previous,"Missing/out-of-order funding SQL: "+prefix); previous=found;
        }
    }
    @Test void boundedDateProgressCommitsAndRecoveryDoesNotRescanOldDates() {
        LocalDate first=LocalDate.now().minusDays(70); FinancialOrder order=order(user,first,70);
        source.commits.set(0); clearInvocations(receipts);
        yields.calculateDailyYield();
        assertTrue(source.commits.get()>0,"A standalone accrual segment must physically commit");
        assertEquals(31,count(order)); assertEquals(first.plusDays(30),current(order).getLastAccruedDate());
        assertEquals("IN_PROGRESS",current(order).getStatus()); money(user,"1000","100");
        verify(receipts).lockByOrderAndDateRange(order.getId(),first,first.plusDays(30));
        clearInvocations(receipts); yields.calculateDailyYield();
        assertEquals(62,count(order)); assertEquals(first.plusDays(61),current(order).getLastAccruedDate());
        verify(receipts).lockByOrderAndDateRange(order.getId(),first.plusDays(31),first.plusDays(61));
        clearInvocations(receipts); yields.calculateDailyYield(); yields.calculateDailyYield();
        assertEquals(70,count(order)); assertEquals("COMPLETED",current(order).getStatus());
        assertEquals(0,new BigDecimal("70").compareTo(current(order).getAccruedYield())); money(user,"1100","0");
        verify(receipts).lockByOrderAndDateRange(order.getId(),first.plusDays(62),first.plusDays(69));
        verify(receipts,never()).lockByOrder(anyLong());
        assertFalse(SqlObserver.scans.get().isEmpty(),"Observe the real paginated scanner SQL");
        assertTrue(SqlObserver.scans.get().stream().noneMatch(Boolean::booleanValue),"Standalone ID scans must not inherit funding transactions");
    }

    @Test void sparseLegacyPaidReceiptsArePreservedAndOnlyMissingDatesAreInserted() {
        LocalDate first=LocalDate.now().minusDays(3); FinancialOrder order=order(user,first,7);
        tx(() -> {
            for(int offset : new int[]{0,2}) {
                FinancialYieldRecord record=new FinancialYieldRecord(); record.setOrderId(order.getId()); record.setUserId(user);
                record.setProductId(product.getId()); record.setProductName(product.getName()); record.setYieldDate(first.plusDays(offset));
                record.setDailyYield(BigDecimal.ONE); record.setCumulativeYield(BigDecimal.valueOf(offset+1));
                record.setStatus("PAID"); record.setPaidAt(first.plusDays(offset).atStartOfDay()); receipts.save(record);
            }
        });
        yields.calculateDailyYield(); yields.calculateDailyYield();
        assertEquals(4,count(order)); assertEquals(LocalDate.now(),current(order).getLastAccruedDate());
        assertEquals(0,new BigDecimal("4").compareTo(current(order).getAccruedYield()));
        assertEquals(2L,database.queryForObject("SELECT COUNT(*) FROM financial_yield_record WHERE tenant_id=? AND order_id=? AND status='PAID'",Long.class,tenant,order.getId()));
        assertEquals(2,audits("FINANCIAL_YIELD_CALCULATE")); money(user,"1000","100");
    }

    @ParameterizedTest @ValueSource(strings={"receipt","progress","wallet","status","audit"})
    void everyAccrualMaturityWriteFailureRollsBackProgressReceiptsPrincipalAndAudit(String point) {
        LocalDate first=LocalDate.now().minusDays(3); FinancialOrder order=order(user,first,3);
        String table,check;
        switch(point) {
            case "receipt": table="financial_yield_record"; check="order_id<>"+order.getId()+" OR yield_date<'"+first.plusDays(1)+"'"; break;
            case "progress": table="financial_order"; check="id<>"+order.getId()+" OR last_accrued_date IS NULL"; break;
            case "wallet": table="asset_account"; check="user_id<>"+user+" OR available=1000"; break;
            case "status": table="financial_order"; check="id<>"+order.getId()+" OR status<>'COMPLETED'"; break;
            default: table="control_audit_log"; check="tenant_id<>"+tenant+" OR action<>'FINANCIAL_MATURE'";
        }
        int rollbacks=source.rollbacks.get();
        constraintFailure(table,check,yields::calculateDailyYield);
        assertTrue(source.rollbacks.get()>rollbacks,"Physical rollback is required");
        assertEquals(0,count(order)); assertNull(current(order).getLastAccruedDate()); assertNull(current(order).getAccruedYield());
        assertEquals("IN_PROGRESS",current(order).getStatus()); money(user,"1000","100");
        assertEquals(0,audits("FINANCIAL_YIELD_CALCULATE")); assertEquals(0,audits("FINANCIAL_MATURE"));
        yields.calculateDailyYield(); yields.calculateDailyYield();
        assertEquals(3,count(order)); assertEquals(1,audits("FINANCIAL_MATURE")); money(user,"1100","0");
    }

    @Test void calculationAuditFailureRollsBackTheDateReceiptAndProgress() {
        FinancialOrder order=order(user,LocalDate.now(),7);
        constraintFailure("control_audit_log","tenant_id<>"+tenant+" OR action<>'FINANCIAL_YIELD_CALCULATE'",yields::calculateDailyYield);
        assertEquals(0,count(order)); assertNull(current(order).getLastAccruedDate()); assertNull(current(order).getAccruedYield());
        money(user,"1000","100"); yields.calculateDailyYield(); yields.calculateDailyYield();
        assertEquals(1,count(order)); assertEquals(1,audits("FINANCIAL_YIELD_CALCULATE"));
    }

    @ParameterizedTest @ValueSource(strings={"wallet","receipt","audit"})
    void everyPayoutWriteFailureRollsBackWalletPaidMarkerAndAudit(String point) {
        FinancialOrder order=order(user,LocalDate.now(),7); yields.calculateDailyYield(); long id=receipt(order);
        String table=point.equals("wallet")?"asset_account":point.equals("receipt")?"financial_yield_record":"control_audit_log";
        String check=point.equals("wallet")?"user_id<>"+user+" OR available=1000":point.equals("receipt")?"id<>"+id+" OR status<>'PAID'":"tenant_id<>"+tenant+" OR action<>'FINANCIAL_YIELD_PAY'";
        constraintFailure(table,check,() -> yields.payoutYield(id));
        money(user,"1000","100"); assertUnpaid(id); assertEquals(0,audits("FINANCIAL_YIELD_PAY"));
        yields.payoutYield(id); yields.payoutYield(id); money(user,"1001","100"); assertEquals(1,audits("FINANCIAL_YIELD_PAY"));
    }

    @Test void fullBatchPrelocksAllUsersThenAssetsAndSecondAuditFailureRollsBackEveryPayout() {
        final long[] second={0}; tx(() -> second[0]=newUser());
        FinancialOrder laterOwner=order(second[0],LocalDate.now(),7), firstOwner=order(user,LocalDate.now(),7);
        yields.calculateDailyYield(); long lowerId=receipt(laterOwner), higherId=receipt(firstOwner);
        assertTrue(lowerId<higherId); clearInvocations(users,assets,receipts);
        constraintFailure("control_audit_log","tenant_id<>"+tenant+" OR action<>'FINANCIAL_YIELD_PAY' OR object_ref<>'"+higherId+"'",yields::payoutAllPendingYields);
        money(user,"1000","100"); money(second[0],"1000","100"); assertUnpaid(lowerId); assertUnpaid(higherId);
        assertEquals(0,audits("FINANCIAL_YIELD_PAY"));
        InOrder locks=inOrder(users,assets,receipts);
        locks.verify(users).lockById(Math.min(user,second[0])); locks.verify(users).lockById(Math.max(user,second[0]));
        locks.verify(assets).lockByUserId(Math.min(user,second[0])); locks.verify(assets).lockByUserId(Math.max(user,second[0]));
        locks.verify(receipts).lockById(lowerId); locks.verify(receipts).lockById(higherId);
        yields.payoutAllPendingYields(); yields.payoutAllPendingYields();
        money(user,"1001","100"); money(second[0],"1001","100"); assertEquals(2,audits("FINANCIAL_YIELD_PAY"));
    }

    @Test void sameUserSecondPayoutAuditFailureRollsBackEveryEarlierCredit() {
        FinancialOrder order=order(user,LocalDate.now().minusDays(2),7); yields.calculateDailyYield();
        List<Long> ids=database.queryForList("SELECT id FROM financial_yield_record WHERE tenant_id=? AND order_id=? ORDER BY id",Long.class,tenant,order.getId());
        assertEquals(3,ids.size());
        constraintFailure("control_audit_log","tenant_id<>"+tenant+" OR action<>'FINANCIAL_YIELD_PAY' OR object_ref<>'"+ids.get(1)+"'",yields::payoutAllPendingYields);
        money(user,"1000","100"); for(Long id : ids) assertUnpaid(id); assertEquals(0,audits("FINANCIAL_YIELD_PAY"));
        yields.payoutAllPendingYields(); yields.payoutAllPendingYields();
        money(user,"1003","100"); assertEquals(3,audits("FINANCIAL_YIELD_PAY"));
    }
    @Test void purchaseAndRedemptionUseUserAssetReceiptOrderSequenceAndReplayOnce() {
        FinancialOrder order=financial.purchaseProduct(user,product.getId(),new BigDecimal("100"),"s3-financial-request");
        assertEquals(LocalDate.now().minusDays(1),order.getLastAccruedDate()); assertEquals(0,order.getAccruedYield().signum());
        clearInvocations(users,assets,orders); SqlObserver.statements.get().clear();
        assertEquals(order.getId(),financial.purchaseProduct(user,product.getId(),new BigDecimal("100.0"),"s3-financial-request").getId());
        InOrder purchase=inOrder(users,assets,orders);
        purchase.verify(users).lockById(user); purchase.verify(assets).lockByUserId(user);
        // Retain the receipt lock-order assertion against actual SQL, not the old missing-key locking repository lookup.
        assertSqlOrder("select * from user_account ","select * from asset_account ","select id from financial_order ","select * from financial_order ");
        assertTrue(SqlObserver.statements.get().stream().filter(sql -> sql.startsWith("select * from financial_order ")).allMatch(sql -> sql.contains("for update")));
        verify(orders,never()).findByTenantIdAndUserIdAndRequestKey(anyLong(),anyLong(),anyString());
        money(user,"900","200"); clearInvocations(users,assets,orders);
        financial.earlyRedeem(user,order.getId());
        InOrder redeem=inOrder(users,assets,orders); redeem.verify(users).lockById(user); redeem.verify(assets).lockByUserId(user); redeem.verify(orders).lockById(order.getId());
        financial.earlyRedeem(user,order.getId()); money(user,"990","100"); assertEquals(1,audits("FINANCIAL_REDEEM"));
    }

    @Test void preloadedManagedOrderYieldAndWalletCannotOverwriteACommittedReplay() throws Exception {
        FinancialOrder order=order(user,LocalDate.now(),7); yields.calculateDailyYield(); long id=receipt(order);
        new TransactionTemplate(manager).execute(status -> {
            assertEquals("IN_PROGRESS",orders.findByTenantIdAndId(tenant,order.getId()).get().getStatus());
            assertEquals("PENDING",receipts.findByTenantIdAndId(tenant,id).get().getStatus());
            assets.findByTenantIdAndUserIdAndCoin(tenant,user,"FUND").get();
            try { parallel(() -> { financial.earlyRedeem(user,order.getId()); yields.payoutYield(id); }); }
            catch (Exception failure) { throw new RuntimeException(failure); }
            financial.earlyRedeem(user,order.getId()); yields.payoutYield(id); return null;
        });
        money(user,"1091","0"); assertEquals(1,audits("FINANCIAL_REDEEM")); assertEquals(1,audits("FINANCIAL_YIELD_PAY"));
    }

    @Test void concurrentAccrualAndConcurrentFullBatchReplayMoveEachDateAndPrincipalOnce() throws Exception {
        FinancialOrder order=order(user,LocalDate.now().minusDays(3),3);
        parallel(yields::calculateDailyYield,yields::calculateDailyYield);
        assertEquals(3,count(order)); assertEquals("COMPLETED",current(order).getStatus()); money(user,"1100","0");
        parallel(yields::payoutAllPendingYields,yields::payoutAllPendingYields);
        money(user,"1103","0"); assertEquals(3,audits("FINANCIAL_YIELD_PAY")); assertEquals(1,audits("FINANCIAL_MATURE"));
    }

    @Test void mysqlRepeatableReadWaiterUsesCurrentPaidReceiptAfterActualUserLockWait() throws Exception {
        repeatableReadPayoutAfterActualUserLockWait(true);
    }
    @Test void mysqlRepeatableReadFreshProxyBatchWaiterUsesCurrentRowsAfterActualUserLockWait() throws Exception {
        repeatableReadPayoutAfterActualUserLockWait(false);
    }
    private void repeatableReadPayoutAfterActualUserLockWait(boolean preloaded) throws Exception {
        Assumptions.assumeTrue(source.mysql,"A real identified MySQL/RR fixture is required; H2 is not this proof");
        FinancialOrder order=order(user,LocalDate.now(),7); yields.calculateDailyYield(); long id=receipt(order);
        CountDownLatch locked=new CountDownLatch(1), snapshotReady=new CountDownLatch(1), release=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong holderConnection=new java.util.concurrent.atomic.AtomicLong(), waiterConnection=new java.util.concurrent.atomic.AtomicLong();
        ExecutorService threads=Executors.newFixedThreadPool(2);
        try {
            Future<?> holder=threads.submit(() -> {
                try(TenantContext.Scope ignored=TenantContext.open(tenant)) {
                    control(); new TransactionTemplate(manager).execute(status -> {
                        assertEquals("REPEATABLE-READ",database.queryForObject("SELECT @@tx_isolation",String.class));
                        holderConnection.set(database.queryForObject("SELECT CONNECTION_ID()",Long.class));
                        users.lockById(user).get(); locked.countDown();
                        try { if(!release.await(20,TimeUnit.SECONDS))throw new AssertionError("Release of the owned funding lock timed out"); }
                        catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
                        yields.payoutYield(id); return null;
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            Future<?> waiter=threads.submit(() -> {
                try(TenantContext.Scope ignored=TenantContext.open(tenant)) {
                    control(); new TransactionTemplate(manager).execute(status -> {
                        assertEquals("REPEATABLE-READ",database.queryForObject("SELECT @@tx_isolation",String.class));
                        waiterConnection.set(database.queryForObject("SELECT CONNECTION_ID()",Long.class));
                        if(preloaded) {
                            assertEquals("PENDING",receipts.findByTenantIdAndId(tenant,id).get().getStatus());
                            assertEquals(0,new BigDecimal("1000").compareTo(assets.findByTenantIdAndUserIdAndCoin(tenant,user,"FUND").get().getAvailable()));
                        } else {
                            // A consistent snapshot without any managed funding/order entities reproduces the fresh-proxy path.
                            assertEquals("PENDING",database.queryForObject("SELECT status FROM financial_yield_record WHERE tenant_id=? AND id=?",String.class,tenant,id));
                            assertEquals(1,receipts.pendingOwners().size());
                            org.hibernate.engine.spi.SessionImplementor session=entityManager.unwrap(org.hibernate.engine.spi.SessionImplementor.class);
                            assertTrue(session.getPersistenceContextInternal().getEntitiesByKey().isEmpty(),"No managed entity may hide the fresh-proxy RR case");
                        }
                        snapshotReady.countDown();
                        if(preloaded) yields.payoutYield(id); else yields.payoutAllPendingYields();
                        return null;
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertTrue(snapshotReady.await(10,TimeUnit.SECONDS));
            // A separate read-only witness connection does not borrow a nested funding-pool connection.
            boolean observedWait=false;
            try(Connection observer=java.sql.DriverManager.getConnection(source.pool.getJdbcUrl(),source.pool.getUsername(),source.pool.getPassword());
                java.sql.PreparedStatement statement=observer.prepareStatement("SELECT COUNT(*) FROM information_schema.innodb_lock_waits w JOIN information_schema.innodb_trx waiting ON waiting.trx_id=w.requesting_trx_id JOIN information_schema.innodb_trx holding ON holding.trx_id=w.blocking_trx_id WHERE waiting.trx_mysql_thread_id=? AND holding.trx_mysql_thread_id=?")) {
                statement.setLong(1,waiterConnection.get()); statement.setLong(2,holderConnection.get());
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(System.nanoTime()<deadline) {
                    try(java.sql.ResultSet row=statement.executeQuery()) { row.next(); if(row.getLong(1)>0) { observedWait=true; break; } }
                    Thread.sleep(25);
                }
            }
            assertTrue(observedWait,"The second physical MySQL transaction must actually wait on the first user's funding lock");
            release.countDown(); holder.get(30,TimeUnit.SECONDS); waiter.get(30,TimeUnit.SECONDS);
        } finally { release.countDown(); threads.shutdownNow(); }
        money(user,"1001","100"); assertEquals(1,audits("FINANCIAL_YIELD_PAY"));
        assertEquals("PAID",receipts.findByTenantIdAndId(tenant,id).get().getStatus());
    }
    @Test void schedulerWithoutControlIdentityStillWritesSuccessAuditAndAuditFailureRollsBack() {
        FinancialOrder order=order(user,LocalDate.now().minusDays(3),3);
        SecurityContextHolder.clearContext();
        constraintFailure("control_audit_log","tenant_id<>"+tenant+" OR action<>'FINANCIAL_MATURE'",yields::calculateDailyYield);
        assertEquals(0,count(order)); assertNull(current(order).getLastAccruedDate()); money(user,"1000","100");
        yields.calculateDailyYield(); yields.calculateDailyYield();
        assertEquals(3,count(order)); money(user,"1100","0");
        assertEquals(3L,database.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND actor_id IS NULL AND action='FINANCIAL_YIELD_CALCULATE'",Long.class,tenant));
        assertEquals(1L,database.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND actor_id IS NULL AND action='FINANCIAL_MATURE'",Long.class,tenant));
        yields.payoutAllPendingYields(); yields.payoutAllPendingYields(); money(user,"1103","0");
        assertEquals(3L,database.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND actor_id IS NULL AND action='FINANCIAL_YIELD_PAY'",Long.class,tenant));
    }
    @Test void scheduleAndRecoveryUseContextOnlyAndTransactionsAreNotDependentOnSelfInvocation() {
        FinancialOrder order=order(user,LocalDate.now(),7); source.commits.set(0);
        // Enumerate a real owned fixture tenant and run the actual JobRunner context-only callback.
        database.update("INSERT INTO tenant (id,code,name,status,template_version,policy_version,session_version,config_ready,domain_verified,row_version,entry_enabled,entry_verified,domain_version,created_at) VALUES (?,?,?,'DISABLED','safe-v1',0,0,false,false,0,false,false,0,?)",
                tenant,"s3-financial-"+tenant,"S3 scheduled financial fixture",java.sql.Timestamp.valueOf(LocalDateTime.now()));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        TenantContext.clear();
        yields.scheduledYield(); yields.recoverYield();
        assertTrue(source.commits.get()>0,"The real self-invoked schedule callback must physically commit");
        verify(jobs).eachContext(eq("financial-yield"),any()); verify(jobs).eachContext(eq("financial-yield-recovery"),any());
        verify(jobs,never()).each(anyString(),any());
        TenantContext.open(tenant);
        assertEquals(1,count(order)); assertEquals(1,audits("FINANCIAL_YIELD_CALCULATE"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
    void parallel(Runnable... actions) throws Exception {
        ExecutorService threads=Executors.newFixedThreadPool(actions.length); CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(Runnable action : actions) futures.add(threads.submit(() -> {
                try(TenantContext.Scope ignored=TenantContext.open(tenant)) {
                    control(); start.await(); action.run();
                } catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
                finally { SecurityContextHolder.clearContext(); SqlObserver.scans.remove(); SqlObserver.statements.remove(); }
            }));
            start.countDown(); for(Future<?> future : futures) future.get(30,TimeUnit.SECONDS);
        } finally { threads.shutdownNow(); }
    }
}
