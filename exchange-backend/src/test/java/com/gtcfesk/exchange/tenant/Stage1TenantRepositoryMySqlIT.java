package com.gtcfesk.exchange.tenant;

import com.gtcfesk.exchange.activity.*;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.support.AnnouncementReceipt;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import javax.persistence.*;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.hibernate.StaleStateException;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL JPA and tenant-task boundaries only; no application schedulers or H2. Synthetic rows never reuse business data. */
class Stage1TenantRepositoryMySqlIT {
    private static DataSource source;
    private static AnnotationConfigApplicationContext context;
    private static EntityManagerFactory factory;
    private static TransactionTemplate transaction;
    private static final List<String> writes=new ArrayList<>();
    private long a,b;
    private boolean seeded;
    private static volatile java.util.concurrent.CountDownLatch registrationInserts;
    private static final java.util.concurrent.atomic.AtomicInteger registrationInsertCount=new java.util.concurrent.atomic.AtomicInteger();

    public static class SqlObserver implements StatementInspector {
        @Override public String inspect(String sql) {
            String lower=sql.toLowerCase(Locale.ROOT);
            if(lower.startsWith("update asset_account ") || lower.startsWith("delete from asset_account ")) writes.add(lower);
            java.util.concurrent.CountDownLatch barrier=registrationInserts;
            if(barrier!=null && lower.startsWith("insert into user_account ")){
                registrationInsertCount.incrementAndGet();barrier.countDown();
                try { if(!barrier.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Both registrations must finish their real prechecks before either INSERT"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Registration race interrupted",e);}
            }
            return sql; // Observe actual Hibernate SQL, never rewrite the statement under test.
        }
    }
    @Configuration
    @EnableJpaRepositories(basePackages="com.gtcfesk.exchange",repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
    public static class JpaOnly {
        @Bean DataSource dataSource(){return source;}
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(){
            LocalContainerEntityManagerFactoryBean bean=new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(source);bean.setPackagesToScan("com.gtcfesk.exchange");
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties properties=new Properties();
            properties.setProperty("hibernate.hbm2ddl.auto","validate");
            properties.setProperty("hibernate.dialect","org.hibernate.dialect.MySQL57Dialect");
            properties.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
            properties.setProperty("hibernate.session_factory.statement_inspector",SqlObserver.class.getName());
            bean.setJpaProperties(properties);return bean;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory value){return new JpaTransactionManager(value);}
    }
    @BeforeAll static void open() throws Exception {
        source=DedicatedMysqlFixture.fromProperty("stage1.mysql.fixture");
        context=new AnnotationConfigApplicationContext(JpaOnly.class);
        factory=context.getBean(EntityManagerFactory.class);
        transaction=new TransactionTemplate(context.getBean(JpaTransactionManager.class));
    }
    @AfterAll static void close(){if(context!=null)context.close();TenantContext.clear();}
    @BeforeEach void seed() throws SQLException {
        writes.clear();seeded=false;
        a=System.currentTimeMillis()*1000L+new Random().nextInt(400);b=a+1;
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try {
                for(long tenant:new long[]{a,b}){
                    execute(c,"INSERT INTO tenant(id,code,name,status,created_at) VALUES(?,?,'Stage1 JPA synthetic','MAINTENANCE',UTC_TIMESTAMP())",tenant,"jpa-"+tenant);
                    execute(c,"INSERT INTO user_account(id,tenant_id,email,phone,password_hash,status,row_version) VALUES(?,?,'same@stage1-jpa.invalid','+10000000000','NOT_A_LOGIN_PASSWORD','normal',0)",tenant,tenant);
                    execute(c,"INSERT INTO asset_account(id,tenant_id,user_id,coin,row_version) VALUES(?,?,?,'STAGE1_JPA',0)",tenant,tenant,tenant);
                    execute(c,"INSERT INTO activity_campaign(id,tenant_id,name,translations,amount,budget) VALUES(?,?,'Stage1 JPA synthetic','{}',0,0)",tenant,tenant);
                    execute(c,"INSERT INTO activity_selection(id,tenant_id,campaign_id,operation_id,filter_hash,created_at) VALUES(?,?,?,'same-operation','synthetic',UTC_TIMESTAMP())",tenant,tenant,tenant);
                    execute(c,"INSERT INTO activity_selection_member(id,tenant_id,selection_id,user_id) VALUES(?,?,?,?)",tenant,tenant,tenant,tenant);
                    execute(c,"INSERT INTO activity_send_receipt(id,tenant_id,campaign_id,operation_id,payload_hash,result_json) VALUES(?,?,?,'same-operation','synthetic','{}')",tenant,tenant,tenant);
                    execute(c,"INSERT INTO trial_grant(id,tenant_id,user_id,campaign_id,request_key,claimed_at) VALUES(?,?,?,?,'same-request',UTC_TIMESTAMP())",tenant,tenant,tenant,tenant);
                    execute(c,"INSERT INTO announcement(id,tenant_id,title,content,language) VALUES(?,?,'Stage1 JPA synthetic','Evidence','en')",tenant,tenant);
                    execute(c,"INSERT INTO announcement_receipt(id,tenant_id,user_id,announcement_id,read_at) VALUES(?,?,?,?,UTC_TIMESTAMP())",tenant,tenant,tenant,tenant);
                }
                c.commit();seeded=true;
            }catch(SQLException e){c.rollback();throw e;}
        }
    }
    @AfterEach void clean() throws SQLException {
        TenantContext.clear();
        if(!seeded)return;
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try {
                for(String table:Arrays.asList("announcement_receipt","trial_grant","activity_send_receipt","activity_selection_member","activity_selection","asset_account","announcement","activity_campaign","user_account"))
                    execute(c,"DELETE FROM "+table+" WHERE tenant_id IN (?,?)",a,b);
                execute(c,"DELETE FROM tenant WHERE id IN (?,?)",a,b);c.commit();
            }catch(SQLException e){c.rollback();throw e;}
        }
    }
    private static void execute(Connection c,String sql,Object... args) throws SQLException {
        try(PreparedStatement statement=c.prepareStatement(sql)){
            for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);
            statement.executeUpdate();
        }
    }
    private static EntityManager em(){return Objects.requireNonNull(EntityManagerFactoryUtils.getTransactionalEntityManager(factory));}
    private <T>T bean(Class<T> type){return context.getBean(type);}
    private void rollback(Runnable action){transaction.execute(status->{status.setRollbackOnly();action.run();return null;});}
    private void assertForeignUnchanged() throws SQLException {
        try(Connection c=source.getConnection();PreparedStatement statement=c.prepareStatement("SELECT tenant_id,user_id,coin,available,row_version FROM asset_account WHERE id=?")){
            statement.setLong(1,b);
            try(ResultSet row=statement.executeQuery()){
                assertTrue(row.next());assertEquals(b,row.getLong(1));assertEquals(b,row.getLong(2));
                assertEquals("STAGE1_JPA",row.getString(3));assertEquals(0,row.getBigDecimal(4).signum());
                assertEquals(0,row.getLong(5));assertFalse(row.next());
            }
        }
    }
    private static void assertStale(Runnable action){
        RuntimeException error=assertThrows(RuntimeException.class,action::run);
        for(Throwable cause=error;cause!=null;cause=cause.getCause())
            if(cause instanceof StaleStateException || cause instanceof OptimisticLockException)return;
        throw new AssertionError("Expected a zero-row tenant-bound physical write, not an unrelated failure",error);
    }
    private AssetAccount forged(){
        AssetAccount asset=new AssetAccount();asset.setId(b);asset.setTenantId(a);asset.setUserId(a);
        asset.setCoin("STAGE1_JPA");asset.setRowVersion(0);asset.setAvailable(BigDecimal.TEN);
        asset.setCreatedAt(LocalDateTime.now());asset.setUpdatedAt(LocalDateTime.now());return asset;
    }

    @Test void everyPrivateMappingUsesTenantPersister(){
        SessionFactoryImplementor sessionFactory=factory.unwrap(SessionFactoryImplementor.class);
        assertTrue(factory.getMetamodel().getEntities().size()>=50);
        factory.getMetamodel().getEntities().stream().filter(entity->TenantOwnedEntity.class.isAssignableFrom(entity.getJavaType()))
            .forEach(entity->assertEquals(TenantEntityPersister.class,sessionFactory.getMetamodel().entityPersister(entity.getJavaType()).getClass(),entity.getName()));
    }
    @Test void repositoryAndEntityManagerHelpersRejectMissingContext(){
        assertThrows(RuntimeException.class,()->bean(UserAccountRepository.class).countByTenantId(a));
        assertThrows(RuntimeException.class,()->bean(AssetAccountRepository.class).save(new AssetAccount()));
        assertThrows(RuntimeException.class,()->bean(ActivitySelectionRepository.class).countByTenantId(a));
        assertThrows(RuntimeException.class,()->bean(ActivitySelectionMemberRepository.class).countByTenantId(a));
        assertThrows(RuntimeException.class,()->bean(ActivitySendReceiptRepository.class).countByTenantId(a));
        assertThrows(RuntimeException.class,()->bean(TrialGrantRepository.class).countByTenantId(a));
        assertThrows(RuntimeException.class,()->rollback(()->TenantEntities.find(em(),AnnouncementReceipt.class,a)));
    }
    @Test void repositoryReadsPaginationSpecificationLockAndBulkUpdateStayInA(){
        UserAccountRepository users=bean(UserAccountRepository.class);
        try(TenantContext.Scope ignored=TenantContext.open(a)){
            rollback(()->{
                assertTrue(users.findByTenantIdAndId(a,a).isPresent());assertFalse(users.findByTenantIdAndId(a,b).isPresent());
                assertTrue(users.findAllByTenantId(a,PageRequest.of(0,100)).stream().allMatch(u->u.getTenantId().equals(a)));
                assertEquals(1,users.findAllByTenantId(a,(root,query,builder)->builder.conjunction()).size());
                assertFalse(users.lockById(b).isPresent());
                assertTrue(users.findDepositCustomers(null,"%","%",PageRequest.of(0,100)).stream().allMatch(u->u.getTenantId().equals(a)));
                assertEquals(0,users.touchActive(b,LocalDateTime.now(),LocalDateTime.now().plusDays(1)));
            });
            assertThrows(RuntimeException.class,()->users.findAllByTenantId(b));
        }
        assertNull(TenantContext.currentTenantId());
    }
    @Test void fiveNewEntitiesAndTheirActualRepositoriesCannotReadB(){
        try(TenantContext.Scope ignored=TenantContext.open(a)){
            rollback(()->{
                for(Class<? extends TenantOwnedEntity> type:Arrays.asList(ActivitySelection.class,ActivitySelectionMember.class,ActivitySendReceipt.class,TrialGrant.class,AnnouncementReceipt.class)){
                    assertNotNull(TenantEntities.find(em(),type,a),type.getSimpleName());
                    assertNull(TenantEntities.find(em(),type,b),type.getSimpleName());
                }
                assertTrue(bean(ActivitySelectionRepository.class).lock(a,"same-operation").isPresent());
                assertFalse(bean(ActivitySelectionRepository.class).lock(b,"same-operation").isPresent());
                assertTrue(bean(ActivitySelectionRepository.class).findByTenantIdAndCampaignIdAndOperationId(a,a,"same-operation").isPresent());
                assertFalse(bean(ActivitySelectionRepository.class).findByTenantIdAndCampaignIdAndOperationId(a,b,"same-operation").isPresent());
                assertEquals(1,bean(ActivitySelectionMemberRepository.class).findByTenantIdAndSelectionIdOrderByIdAsc(a,a,PageRequest.of(0,100)).getTotalElements());
                assertEquals(0,bean(ActivitySelectionMemberRepository.class).findByTenantIdAndSelectionIdOrderByIdAsc(a,b,PageRequest.of(0,100)).getTotalElements());
                assertTrue(bean(ActivitySendReceiptRepository.class).findByTenantIdAndCampaignIdAndOperationId(a,a,"same-operation").isPresent());
                assertFalse(bean(ActivitySendReceiptRepository.class).findByTenantIdAndCampaignIdAndOperationId(a,b,"same-operation").isPresent());
                assertTrue(bean(TrialGrantRepository.class).findByTenantIdAndUserIdAndRequestKey(a,a,"same-request").isPresent());
                assertFalse(bean(TrialGrantRepository.class).findByTenantIdAndUserIdAndRequestKey(a,b,"same-request").isPresent());
            });
        }
    }
    @Test void saveDirtyFlushAndDeleteUseActualOwnerPredicate(){
        try(TenantContext.Scope ignored=TenantContext.open(a)){
            rollback(()->{
                AssetAccount created=new AssetAccount();created.setUserId(a);created.setCoin("STAGE1_NEW");
                bean(AssetAccountRepository.class).saveAndFlush(created);
                assertEquals(a,created.getTenantId());assertNotNull(created.getId());
                created.setAvailable(BigDecimal.ONE);em().flush();
                bean(AssetAccountRepository.class).deleteByTenantIdAndId(a,created.getId());em().flush();
                assertFalse(bean(AssetAccountRepository.class).existsByTenantIdAndId(a,created.getId()));
            });
        }
        assertTrue(writes.stream().anyMatch(sql->sql.startsWith("update")&&sql.contains("tenant_id="+a)&&sql.contains("row_version=?")));
        assertTrue(writes.stream().anyMatch(sql->sql.startsWith("delete")&&sql.contains("tenant_id="+a)&&sql.contains("row_version=?")));
        assertTrue(writes.stream().allMatch(sql->sql.substring(sql.indexOf(" where ")).contains("tenant_id")));
    }
    @Test void repositoryCannotOverwriteForeignIdOrDeletePartialBatch() throws SQLException {
        try(TenantContext.Scope ignored=TenantContext.open(a)){
            assertThrows(RuntimeException.class,()->rollback(()->bean(AssetAccountRepository.class).saveAndFlush(forged())));
            assertThrows(RuntimeException.class,()->rollback(()->bean(AssetAccountRepository.class).delete(forged())));
            assertThrows(RuntimeException.class,()->rollback(()->bean(AssetAccountRepository.class).deleteAllByTenantIdAndIdIn(a,Arrays.asList(a,b))));
            assertTrue(bean(AssetAccountRepository.class).existsByTenantIdAndId(a,a));
        }
        assertForeignUnchanged();
    }
    @Test void forgedDetachedDirtyUpdateHasPhysicalTenantPredicate() throws SQLException {forgedDml(false);}
    @Test void forgedDetachedDeleteHasPhysicalTenantPredicate() throws SQLException {forgedDml(true);}
    private void forgedDml(boolean deleting) throws SQLException {
        try(TenantContext.Scope ignored=TenantContext.open(a)){
            assertStale(()->rollback(()->{
                Session session=em().unwrap(Session.class);AssetAccount asset=forged();session.update(asset);
                if(deleting){session.setReadOnly(asset,true);session.delete(asset);}em().flush();
            }));
        }
        assertTrue(writes.stream().anyMatch(sql->sql.startsWith(deleting?"delete":"update")&&sql.substring(sql.indexOf(" where ")).contains("tenant_id="+a)));
        assertForeignUnchanged();
    }
    @Test void forceVersionIncrementCannotTouchForeignRowAndStillWorksForOwner() throws SQLException {
        org.hibernate.persister.entity.EntityPersister persister=factory.unwrap(SessionFactoryImplementor.class).getMetamodel().entityPersister(AssetAccount.class);
        try(TenantContext.Scope ignored=TenantContext.open(a)){
            assertStale(()->rollback(()->persister.forceVersionIncrement(b,0L,em().unwrap(SharedSessionContractImplementor.class))));
            rollback(()->assertEquals(1L,persister.forceVersionIncrement(a,0L,em().unwrap(SharedSessionContractImplementor.class))));
        }
        assertTrue(writes.stream().anyMatch(sql->sql.substring(sql.indexOf(" where ")).contains("tenant_id=?")));
        assertForeignUnchanged();
    }

    private org.springframework.jdbc.core.JdbcTemplate jobJdbc(){return new org.springframework.jdbc.core.JdbcTemplate(source);}
    private TenantJobRunner jobs(){return new TenantJobRunner(jobJdbc(),context.getBean(JpaTransactionManager.class));}
    private String jobTitle(long tenant){return jobJdbc().queryForObject("SELECT title FROM announcement WHERE tenant_id=? AND id=?",String.class,tenant,tenant);}
    private void markJob(String title){long tenant=TenantContext.requireTenantId();assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertEquals(1,jobJdbc().update("UPDATE announcement SET title=? WHERE tenant_id=? AND id=?",title,tenant,tenant));}
    @Test void actualTenantRunnerFailureRollsBackContinuesAndNextTickRetries(){
        TenantJobRunner runner=jobs();List<Long> all=jobJdbc().queryForList("SELECT id FROM tenant ORDER BY id",Long.class),visited=new ArrayList<>();
        assertTrue(all.contains(a));assertTrue(all.contains(b));
        runner.each("stage1-failure",tenant->{assertEquals(tenant,TenantContext.requireTenantId());visited.add(tenant);if(tenant==a){markJob("must rollback");throw new IllegalStateException("intentional stage1 task failure");}if(tenant==b)markJob("B completed");});
        assertEquals(all,visited);assertNull(TenantContext.currentTenantId());assertEquals("Stage1 JPA synthetic",jobTitle(a));assertEquals("B completed",jobTitle(b));
        visited.clear();runner.each("stage1-retry",tenant->{assertEquals(tenant,TenantContext.requireTenantId());visited.add(tenant);if(tenant==a||tenant==b)markJob("retry completed");});
        assertEquals(all,visited);assertEquals("retry completed",jobTitle(a));assertEquals("retry completed",jobTitle(b));assertNull(TenantContext.currentTenantId());
        // Both synthetic tenants are MAINTENANCE: enumeration must not abandon existing settlement responsibility.
        assertEquals("MAINTENANCE",jobJdbc().queryForObject("SELECT status FROM tenant WHERE id=?",String.class,a));
    }
    @Test void actualTenantRunnerCallOneAndFailureRestoreContextAndTransactions(){
        TenantJobRunner runner=jobs();assertThrows(IllegalStateException.class,()->runner.call(a,()->{markJob("call rollback");throw new IllegalStateException("intentional call failure");}));
        assertNull(TenantContext.currentTenantId());assertEquals("Stage1 JPA synthetic",jobTitle(a));
        try(TenantContext.Scope ignored=TenantContext.open(a)){
            assertEquals(a,runner.call(a,TenantContext::requireTenantId));assertEquals(a,TenantContext.requireTenantId());
            assertThrows(RuntimeException.class,()->runner.call(b,TenantContext::requireTenantId));assertEquals(a,TenantContext.requireTenantId());
            assertThrows(IllegalStateException.class,()->runner.each("request-must-not-start-global-job",tenant->fail("must reject before enumeration")));
        }
        assertNull(TenantContext.currentTenantId());runner.one(b,()->markJob("one completed"));assertNull(TenantContext.currentTenantId());assertEquals("one completed",jobTitle(b));
    }
    @Test void sameWorkerTaskFailureAndRetryNeverCarryTenantIntoNextTask() throws Exception {
        TenantJobRunner runner=jobs();java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            worker.submit(()->{assertNull(TenantContext.currentTenantId());assertThrows(IllegalStateException.class,()->runner.call(a,()->{markJob("worker rollback");throw new IllegalStateException("intentional async failure");}));assertNull(TenantContext.currentTenantId());}).get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("Stage1 JPA synthetic",jobTitle(a));
            worker.submit(()->{assertNull(TenantContext.currentTenantId());runner.one(b,()->markJob("worker B"));assertNull(TenantContext.currentTenantId());runner.one(a,()->markJob("worker retry A"));assertNull(TenantContext.currentTenantId());}).get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertNull(worker.submit(TenantContext::currentTenantId).get(20,java.util.concurrent.TimeUnit.SECONDS));assertEquals("worker B",jobTitle(b));assertEquals("worker retry A",jobTitle(a));
        } finally {worker.shutdownNow();}
        assertNull(TenantContext.currentTenantId());
    }

    /** Focused identity/transaction IT: only captcha and effective policy/config are fixed boundary collaborators.
     * Repositories, Hibernate/JDBC/MySQL, ID allocation, BCrypt, signed tenant JWT and @Transactional interceptor are real.
     * API/browser acceptance separately exercises the actual Redis captcha and tenant readiness lifecycle.
     */
    private com.gtcfesk.exchange.auth.AuthService registrationService(){
        com.gtcfesk.exchange.admin.SystemConfigService configs=org.mockito.Mockito.mock(com.gtcfesk.exchange.admin.SystemConfigService.class);
        org.mockito.Mockito.when(configs.registrationFields()).thenReturn(com.gtcfesk.exchange.auth.RegistrationFields.defaults());
        com.gtcfesk.exchange.common.JwtUtil jwt=registrationJwt();
        com.gtcfesk.exchange.auth.AuthService service=new com.gtcfesk.exchange.auth.AuthService(
            bean(com.gtcfesk.exchange.repository.VerifyCodeRepository.class),org.mockito.Mockito.mock(com.gtcfesk.exchange.service.EmailService.class),
            bean(UserAccountRepository.class),new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4),bean(AssetAccountRepository.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"jwtUtil",jwt);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"registrationSecurity",org.mockito.Mockito.mock(com.gtcfesk.exchange.security.RegistrationSecurity.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"tenantPolicy",org.mockito.Mockito.mock(com.gtcfesk.exchange.control.TenantPolicyService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"systemConfigService",configs);
        org.springframework.aop.framework.ProxyFactory proxy=new org.springframework.aop.framework.ProxyFactory(service);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(context.getBean(JpaTransactionManager.class),
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (com.gtcfesk.exchange.auth.AuthService)proxy.getProxy();
    }
    private com.gtcfesk.exchange.common.JwtUtil registrationJwt(){
        com.gtcfesk.exchange.common.JwtUtil jwt=new com.gtcfesk.exchange.common.JwtUtil();
        org.springframework.test.util.ReflectionTestUtils.setField(jwt,"secret","stage1-fixture-only-signing-not-a-service-secret");
        org.springframework.test.util.ReflectionTestUtils.setField(jwt,"expireSeconds",60L);
        org.springframework.test.util.ReflectionTestUtils.setField(jwt,"tenants",bean(com.gtcfesk.exchange.control.TenantRepository.class));return jwt;
    }
    private com.gtcfesk.exchange.auth.dto.RegisterRequest registrationRequest(String email,String phone,String password){
        com.gtcfesk.exchange.auth.dto.RegisterRequest request=new com.gtcfesk.exchange.auth.dto.RegisterRequest();
        request.setEmail(email);request.setCountryCode("+65");request.setPhone(phone);request.setPassword(password);request.setConfirmPassword(password);return request;
    }
    private com.gtcfesk.exchange.auth.vo.AuthResponse register(com.gtcfesk.exchange.auth.AuthService service,long tenant,com.gtcfesk.exchange.auth.dto.RegisterRequest request){
        try(TenantContext.Scope ignored=TenantContext.open(tenant)){return service.register(request);}
    }
    private List<String> registrationSnapshot(long tenant){
        List<String> snapshot=new ArrayList<>();
        // Compare credentials/session internally by a digest, never print a password hash or signed token in test failure evidence.
        snapshot.addAll(jobJdbc().queryForList("SELECT SHA2(CONCAT_WS('|',id,tenant_id,email,COALESCE(country_code,''),COALESCE(phone,''),password_hash,COALESCE(current_token,''),status,row_version),256) FROM user_account WHERE tenant_id=? ORDER BY id",String.class,tenant));
        snapshot.addAll(jobJdbc().queryForList("SELECT SHA2(CONCAT_WS('|',id,tenant_id,user_id,coin,available,frozen,row_version),256) FROM asset_account WHERE tenant_id=? ORDER BY id",String.class,tenant));return snapshot;
    }
    private int registrationRows(String table,long tenant){return jobJdbc().queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant);}
    private void assertRegistrationAssets(long tenant,long user){
        assertEquals(new HashSet<>(Arrays.asList("FUND","CONTRACT","OPTION")),new HashSet<>(jobJdbc().queryForList("SELECT coin FROM asset_account WHERE tenant_id=? AND user_id=?",String.class,tenant,user)));
        assertEquals(0,jobJdbc().queryForObject("SELECT COUNT(*) FROM asset_account WHERE tenant_id=? AND user_id=? AND (available<>0 OR frozen<>0)",Integer.class,tenant,user));
    }
    @Test void actualRegistrationDuplicatePhoneIsControlledAndLeavesOriginalUserAndAssetsUnchanged(){
        com.gtcfesk.exchange.auth.AuthService service=registrationService();String first="phone-first-"+a+"@stage1-jpa.invalid",denied="phone-denied-"+a+"@stage1-jpa.invalid";
        register(service,a,registrationRequest(first,"8123-4567","Stage1-only-first"));List<String> before=registrationSnapshot(a),foreign=registrationSnapshot(b);
        com.gtcfesk.exchange.common.BusinessException conflict=assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->register(service,a,registrationRequest(denied,"+65 8123 4567","Stage1-only-denied")));
        assertEquals(400,conflict.getCode());assertEquals("phone exists",conflict.getMessage());assertEquals(before,registrationSnapshot(a));assertEquals(foreign,registrationSnapshot(b));
        assertEquals(0,jobJdbc().queryForObject("SELECT COUNT(*) FROM user_account WHERE tenant_id=? AND email=?",Integer.class,a,denied));assertNull(TenantContext.currentTenantId());
    }
    @Test void actualRegistrationSameNormalizedEmailAndPhoneRemainIndependentAcrossTenants(){
        com.gtcfesk.exchange.auth.AuthService service=registrationService();String email="same-registration-"+a+"@stage1-jpa.invalid";
        com.gtcfesk.exchange.auth.vo.AuthResponse first=register(service,a,registrationRequest(" "+email.toUpperCase(Locale.ROOT)+" ","8123 4567","Stage1-A-password"));
        com.gtcfesk.exchange.auth.vo.AuthResponse second=register(service,b,registrationRequest(email,"+65 8123-4567","Stage1-B-password"));
        com.gtcfesk.exchange.entity.UserAccount left,right;
        try(TenantContext.Scope ignored=TenantContext.open(a)){left=bean(UserAccountRepository.class).findByTenantIdAndEmail(a,email).orElseThrow(AssertionError::new);}
        try(TenantContext.Scope ignored=TenantContext.open(b)){right=bean(UserAccountRepository.class).findByTenantIdAndEmail(b,email).orElseThrow(AssertionError::new);}
        assertNotEquals(left.getId(),right.getId());assertEquals(a,left.getTenantId());assertEquals(b,right.getTenantId());assertEquals(left.getPhone(),right.getPhone());
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwords=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        assertTrue(passwords.matches("Stage1-A-password",left.getPasswordHash()));assertFalse(passwords.matches("Stage1-B-password",left.getPasswordHash()));
        assertTrue(passwords.matches("Stage1-B-password",right.getPasswordHash()));assertFalse(passwords.matches("Stage1-A-password",right.getPasswordHash()));
        assertEquals(a,((Number)registrationJwt().parse(first.getToken()).get("tenantId")).longValue());assertEquals(b,((Number)registrationJwt().parse(second.getToken()).get("tenantId")).longValue());
        assertRegistrationAssets(a,left.getId());assertRegistrationAssets(b,right.getId());assertNull(TenantContext.currentTenantId());
    }
    @Test void actualRegistrationPhoneAndNormalizedEmailRacesRejectSql1062AndRollbackLoser() throws Exception {
        com.gtcfesk.exchange.auth.AuthService service=registrationService();java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for(boolean phoneRace:new boolean[]{true,false}){
                int usersBefore=registrationRows("user_account",a),assetsBefore=registrationRows("asset_account",a);List<String> foreign=registrationSnapshot(b);
                String email="race-"+(phoneRace?"phone":"email")+"-"+a+"@stage1-jpa.invalid";
                com.gtcfesk.exchange.auth.dto.RegisterRequest left=registrationRequest(email,"81234001","Stage1-race-left");
                com.gtcfesk.exchange.auth.dto.RegisterRequest right=registrationRequest(phoneRace?"other-"+email:" "+email.toUpperCase(Locale.ROOT)+" ",phoneRace?"8123-4001":"81234002","Stage1-race-right");
                if(!phoneRace)left.setPhone("81234003");
                registrationInsertCount.set(0);registrationInserts=new java.util.concurrent.CountDownLatch(2);
                java.util.concurrent.Callable<Object> one=()->registrationAttempt(service,left),two=()->registrationAttempt(service,right);
                java.util.concurrent.Future<Object> first=workers.submit(one),second=workers.submit(two);
                Object x=first.get(30,java.util.concurrent.TimeUnit.SECONDS),y=second.get(30,java.util.concurrent.TimeUnit.SECONDS);registrationInserts=null;
                assertEquals(2,registrationInsertCount.get(),"Both real prechecks passed; neither loser was rejected by a mock or preflight");
                assertEquals(1,(x instanceof com.gtcfesk.exchange.auth.vo.AuthResponse?1:0)+(y instanceof com.gtcfesk.exchange.auth.vo.AuthResponse?1:0));
                Object loser=x instanceof com.gtcfesk.exchange.common.BusinessException?x:y;assertTrue(loser instanceof com.gtcfesk.exchange.common.BusinessException);
                com.gtcfesk.exchange.common.BusinessException failure=(com.gtcfesk.exchange.common.BusinessException)loser;assertEquals(400,failure.getCode());assertEquals("registration identity exists",failure.getMessage());
                Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());boolean duplicate=false;
                for(Throwable cause=failure;cause!=null&&seen.add(cause);cause=cause.getCause())if(cause instanceof SQLException&&((SQLException)cause).getErrorCode()==1062)duplicate=true;
                assertTrue(duplicate,"Controlled denial must retain the actual MySQL duplicate category, not an unrelated failure");
                assertEquals(usersBefore+1,registrationRows("user_account",a));assertEquals(assetsBefore+3,registrationRows("asset_account",a));assertEquals(foreign,registrationSnapshot(b));
                com.gtcfesk.exchange.auth.vo.AuthResponse winner=(com.gtcfesk.exchange.auth.vo.AuthResponse)(x instanceof com.gtcfesk.exchange.auth.vo.AuthResponse?x:y);
                long user=Long.parseLong(registrationJwt().parse(winner.getToken()).getSubject().substring("user-".length()));assertRegistrationAssets(a,user);
                assertEquals(0,jobJdbc().queryForObject("SELECT COUNT(*) FROM asset_account aa LEFT JOIN user_account u ON u.tenant_id=aa.tenant_id AND u.id=aa.user_id WHERE aa.tenant_id=? AND u.id IS NULL",Integer.class,a));
                assertNull(TenantContext.currentTenantId());
            }
        } finally {registrationInserts=null;workers.shutdownNow();}
    }
    private Object registrationAttempt(com.gtcfesk.exchange.auth.AuthService service,com.gtcfesk.exchange.auth.dto.RegisterRequest request){
        assertNull(TenantContext.currentTenantId());try{return register(service,a,request);}catch(com.gtcfesk.exchange.common.BusinessException failure){return failure;}
        finally{assertNull(TenantContext.currentTenantId());}
    }
}
