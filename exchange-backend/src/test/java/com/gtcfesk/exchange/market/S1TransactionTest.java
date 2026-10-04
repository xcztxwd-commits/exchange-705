package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring templates/physical JDBC commits; optional isolated MySQL + real Redis. No application boot. */
class S1TransactionTest extends TenantMarketTestContext {
    static { ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("ROOT")).setLevel(ch.qos.logback.classic.Level.WARN); }
    HikariDataSource pool;
    DataSourceTransactionManager manager;
    ControlHistoryStore store;
    PersistentPriceControl controls;
    ForexQuoteMarketService market;
    TenantJobRunner jobs;
    TradingSymbolRepository repository;
    RedisMarketService redis;
    final AtomicInteger commits = new AtomicInteger(), rollbacks = new AtomicInteger(), borrowed = new AtomicInteger();
    final List<String> physical = new CopyOnWriteArrayList<>();
    TradingSymbol symbol;
    org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisConnection;
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    boolean mysql;

    @BeforeEach void setup() throws Exception {
        pool = new HikariDataSource();
        String url = System.getenv("S1_TEST_JDBC"); mysql = url != null;
        if (mysql) assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:33329/performance_test(?:\\?.*)?"));
        pool.setJdbcUrl(mysql ? url : "jdbc:h2:mem:s1_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        pool.setUsername(mysql ? "root" : "sa"); pool.setPassword(mysql ? "performance-test-only" : "");
        pool.setMaximumPoolSize(10);
        DelegatingDataSource observed = new DelegatingDataSource(pool) {
            @Override public Connection getConnection() throws java.sql.SQLException {
                Connection connection = super.getConnection(); borrowed.incrementAndGet();
                long opened = System.nanoTime();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (p,m,a) -> {
                    if (m.getName().equals("commit")) { commits.incrementAndGet(); physical.add("commit"); }
                    if (m.getName().equals("rollback")) { rollbacks.incrementAndGet(); physical.add("rollback"); }
                    if (m.getName().equals("close")) { borrowed.decrementAndGet(); physical.add("connection-ms=" + (System.nanoTime()-opened)/1e6); }
                    try { return m.invoke(connection, a); } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
        manager = new DataSourceTransactionManager(observed);
        store = new ControlHistoryStore(new JdbcTemplate(observed), manager);
        if (mysql) {
            assertEquals(System.getenv("S1_TEST_UUID"), store.db.queryForObject("SELECT @@server_uuid", String.class));
        }
        MarketSqlFixture.schema(store.db);
        // Only this disposable fixture and this test's reserved symbols/tasks are reset.
        for (String table : Arrays.asList("market_control_sample","market_control_plan","market_control_hold","market_control_flow","market_control_publication","market_control_resume"))
            store.db.update("DELETE FROM " + table + " WHERE tenant_id=1 AND task_id LIKE 's1-%'");
        for (String table : Arrays.asList("market_source_event","market_source_quote","market_source_tick","market_source_candle","market_mixed_minute","market_legacy_minute_snapshot","market_simulation_source_candle","market_control_task"))
            store.db.update("DELETE FROM " + table + " WHERE tenant_id=1 AND symbol_id IN (98001,98002)");
        store.db.update("DELETE FROM trading_symbol WHERE id IN (98001,98002)");
        store.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(98001,1),(98002,1)");
        store.db.execute("CREATE TABLE IF NOT EXISTS tenant(id BIGINT PRIMARY KEY)");
        store.db.update("INSERT INTO tenant(id) VALUES(1) ON DUPLICATE KEY UPDATE id=VALUES(id)");
        controls = new PersistentPriceControl(store); market = new ForexQuoteMarketService();
        jobs = new TenantJobRunner(store.db, manager); repository = mock(TradingSymbolRepository.class);
        symbol = symbol(98001, "S1");
        when(repository.findAllByTenantId(1L)).thenReturn(Collections.singletonList(symbol));
        when(repository.findByTenantIdAndId(1L,98001L)).thenAnswer(i -> Optional.of(symbol));
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        redis = new RedisMarketService();
        if (mysql) {
            redisConnection = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("127.0.0.1",33428);
            redisConnection.afterPropertiesSet(); redisTemplate = new org.springframework.data.redis.core.StringRedisTemplate(redisConnection);
            redisTemplate.afterPropertiesSet();
            ReflectionTestUtils.setField(redis,"redisTemplate",redisTemplate);
            redisTemplate.delete("tenant:1:market:price:Metal:S1");
        } else redis = spy(new RedisMarketService());
        ReflectionTestUtils.setField(market,"symbols",repository); ReflectionTestUtils.setField(market,"redis",redis);
        ReflectionTestUtils.setField(market,"controls",controls); ReflectionTestUtils.setField(market,"controlHistory",store);
        ReflectionTestUtils.setField(market,"tenantJobs",jobs);
        // Avoid Redis cold loads in the mock-free transaction assertions.
        if (!mysql) doReturn(null).when(redis).getPrice(anyString());
        market.refreshSymbols();
        commits.set(0); rollbacks.set(0); physical.clear();
    }
    TradingSymbol symbol(long id, String name) {
        TradingSymbol s = new TradingSymbol(); s.setTenantId(1L); s.setId(id); s.setSymbol(name); s.setAlltickSymbol("S1");
        s.setCategory("Metal"); s.setSourceCategory("Metal"); s.setQuoteCurrency("USD"); s.setIsEnabled(true); s.setPricePrecision(8); return s;
    }
    Map<String,Object> quote(long at, int price, String id) {
        Map<String,Object> q = new HashMap<>(); q.put("timestamp",at); q.put("price",price); q.put("eventId",id); return q;
    }
    @AfterEach void close() {
        System.out.println("S1_PHYSICAL mysql=" + mysql + " commits=" + commits + " rollbacks=" + rollbacks + " trace=" + physical);
        if (market != null) market.stop();
        if (redisConnection != null) redisConnection.destroy();
        if (pool != null) pool.close();
    }
    @Test void outerRollbackCannotExposeMemoryPendingRedisOrRealRedis() {
        long now = System.currentTimeMillis();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(status -> {
            market.acceptQuote("S1","Metal",quote(now,100,"rollback"),"http",now);
            assertNull(market.getPrice("S1","Metal").get("price"));
            assertEquals(0, ((Map<?,?>)ReflectionTestUtils.getField(redis,"pendingPrices")).size());
            if (mysql) { redis.flushPrices(); assertNull(redisTemplate.opsForValue().get("tenant:1:market:price:Metal:S1")); }
            throw new IllegalStateException("after-ingress-before-commit");
        }));
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE symbol_id=98001",Integer.class));
        assertEquals(1,rollbacks.get()); assertNull(market.getPrice("S1","Metal").get("price"));
        market.acceptQuote("S1","Metal",quote(now,100,"rollback"),"http",now);
        assertEquals(100,market.getPrice("S1","Metal").get("price"));
        if (mysql) { redis.flushPrices(); assertTrue(redisTemplate.opsForValue().get("tenant:1:market:price:Metal:S1").contains("rollback")); }
    }
    @Test void secondAliasFailureRollsBackWholeSourceEvent() {
        TradingSymbol missing = symbol(98003,"MISSING");
        when(repository.findAllByTenantId(1L)).thenReturn(Arrays.asList(symbol,missing)); market.refreshSymbols();
        long now=System.currentTimeMillis();
        assertThrows(RuntimeException.class, () -> market.acceptQuote("S1","Metal",quote(now,101,"alias-failure"),"http",now));
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE event_id='alias-failure'",Integer.class));
        assertNull(market.getPrice("S1","Metal").get("price")); assertEquals(1,rollbacks.get());
    }
    @Test void aliasWriterReloadsCommittedControlInsteadOfUsingDetachedRegistry() {
        // Simulate a command on another instance: this process still has the earlier registry copy.
        symbol.setControlEnabled(true);symbol.setControlPriceOffset(new BigDecimal("2"));symbol.setRowVersion(1);
        long now=System.currentTimeMillis();
        market.acceptQuote("S1","Metal",quote(now,100,"fresh-config"),"http",now);
        Map<String,Object> minute=store.mixed(98001,now/60000*60000,now).get(0);
        assertEquals(0,new BigDecimal("102").compareTo(ControlHistoryStore.number(minute.get("close_price"))));
    }
    @Test void emptyCacheCannotPublishAnOlderQuoteRejectedByDurableOrdering() {
        long now=System.currentTimeMillis();
        market.acceptQuote("S1","Metal",quote(now,105,"durable-new"),"http",now);
        Object group=((Map<?,?>)ReflectionTestUtils.getField((Object)ReflectionTestUtils.invokeMethod(market,"state"),"groups")).get("Metal");
        ((Map<?,?>)ReflectionTestUtils.getField(group,"quotes")).clear();
        assertFalse(market.acceptQuote("S1","Metal",quote(now-1000,100,"late-after-restart"),"http",now+1));
        assertNull(market.getPrice("S1","Metal").get("price"));
        assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=98001",Integer.class));
    }
    @Test void duplicateAndDelayedCommitCannotOverwriteNewerPrice() throws Exception {
        ExecutorService workers=Executors.newSingleThreadExecutor();
        CountDownLatch persisted=new CountDownLatch(1), release=new CountDownLatch(1);
        long now=System.currentTimeMillis()-1000;
        try {
            Future<?> old=workers.submit(() -> { try(TenantContext.Scope ignored=TenantContext.open(1L)) {
                new TransactionTemplate(manager).execute(status -> {
                    // This earlier synchronization runs after physical commit but before the market publication.
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() { persisted.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e){throw new RuntimeException(e);} }
                    });
                    market.acceptQuote("S1","Metal",quote(now,100,"old"),"http",now); return null;
                });
            }});
            assertTrue(persisted.await(5,TimeUnit.SECONDS));
            market.acceptQuote("S1","Metal",quote(now,102,"new"),"http",now+1);
            release.countDown(); old.get(5,TimeUnit.SECONDS);
            assertEquals(102,market.getPrice("S1","Metal").get("price"));
            market.acceptQuote("S1","Metal",quote(now,102,"new"),"http",now+2);
            assertEquals(2,store.db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE symbol_id=98001",Integer.class));
            if(mysql) {redis.flushPrices(); assertTrue(redisTemplate.opsForValue().get("tenant:1:market:price:Metal:S1").contains("102"));}
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void afterCommitNotificationFailureRetriesWithoutUndoingDurableQuote() {
        RedisMarketService failing=spy(redis); AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(i -> { if(fail.getAndSet(false))throw new IllegalStateException("notification"); return i.callRealMethod(); }).when(failing).savePrice(anyString(),anyMap());
        ReflectionTestUtils.setField(market,"redis",failing);
        long now=System.currentTimeMillis();
        assertDoesNotThrow(() -> market.acceptQuote("S1","Metal",quote(now,103,"notify"),"http",now));
        assertEquals(103,market.getPrice("S1","Metal").get("price"));
        Object group=((Map<?,?>)ReflectionTestUtils.getField((Object)ReflectionTestUtils.invokeMethod(market,"state"),"groups")).get("Metal");
        ReflectionTestUtils.setField(group,"nextAllowed",Long.MAX_VALUE);
        ReflectionTestUtils.invokeMethod(market,"tick",group);
        assertTrue(((Set<?>)ReflectionTestUtils.getField(group,"redisRetry")).isEmpty());
        assertEquals(1,((Map<?,?>)ReflectionTestUtils.getField(failing,"pendingPrices")).size());
        if(mysql) { failing.flushPrices(); assertTrue(redisTemplate.opsForValue().get("tenant:1:market:price:Metal:S1").contains("notify")); }
    }
    @Test void contextOnlyHasNoPhysicalConnectionAndLegacyRunnerKeepsAtomicity() {
        TenantContext.clear();
        try {
            jobs.oneContext("s1",1L,() -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); assertEquals(0,borrowed.get());
                store.locked(98001,() -> { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); assertEquals(1,borrowed.get());return null; });
                assertEquals(1,commits.get()); assertEquals(0,borrowed.get());
            });
            assertNull(TenantContext.currentTenantId());
            assertThrows(RuntimeException.class,() -> jobs.oneContext("s1",1L,() -> {throw new IllegalStateException("scope");}));
            assertNull(TenantContext.currentTenantId());
            jobs.one("unchanged-funds",1L,() -> { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); assertEquals(1,borrowed.get());
                assertThrows(RuntimeException.class,() -> jobs.context(1L,() -> null)); });
            assertEquals(2,commits.get()); assertNull(TenantContext.currentTenantId());
        } finally { TenantContext.open(1L); }
    }
    @Test void secondCandleBatchAndSecondAliasRemainOneCommit() {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<501;i++) rows.add(new LinkedHashMap<>(Map.of("timestamp",1700000400000L+i*60000L,"close_price",100)));
        store.sourceCandles(Arrays.asList(98001L,98002L),"1m",rows,1700100400000L);
        assertEquals(1,commits.get());
        assertEquals(1002,store.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE symbol_id IN (98001,98002)",Integer.class));
        assertThrows(RuntimeException.class,() -> store.sourceCandles(Arrays.asList(98001L,98003L),"1m",rows,1700200400000L));
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE received_at=1700200400000",Integer.class));
        if(mysql) {
            rows.get(500).put("oversize",String.join("",Collections.nCopies(70000,"x")));
            assertThrows(RuntimeException.class,() -> store.sourceCandles(98001,"1m",rows,1700300400000L));
            assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE received_at=1700300400000",Integer.class));
        }
    }
    @Test void encodingRunsBeforeRowLockAndPreparedIdentityCannotCrossSymbols() {
        ControlHistoryStore observed=new ControlHistoryStore(store.db,manager) {
            @Override String encode(Map<String,Object> row) { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return super.encode(row); }
            @Override EncodedPlan encodePlan(TargetControlPlan plan) { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return super.encodePlan(plan); }
        };
        observed.sourceCandles(98001,"1m",Collections.singletonList(Map.of("timestamp",1700000400000L,"close_price",100)),1700000460000L);
        PersistentPriceControl preparedControl=new PersistentPriceControl(observed);
        Map<String,Object> raw=quote(System.currentTimeMillis(),100,"plan"); raw.put("available",true); raw.put("sourceTimestamp",raw.get("timestamp"));
        PersistentPriceControl.Prepared plan=preparedControl.prepare(symbol,raw,new BigDecimal("100"),60,new BigDecimal("100.054"),10,false);
        assertThrows(BalancedControlPlan.Failure.class,() -> preparedControl.startPrepared(symbol(98002,"OTHER"),raw,new BigDecimal("100"),60,new BigDecimal("100.054"),10,false,"wrong",null,plan));
        symbol.setRowVersion(1);
        assertThrows(BalancedControlPlan.Failure.class,() -> preparedControl.startPrepared(symbol,raw,new BigDecimal("100"),60,new BigDecimal("100.054"),10,false,"stale",null,plan));
    }
    @Test void slowTenantRowLockDoesNotHoldSharedMonitorForOtherTenantStatusQuoteAndReceipt() throws Exception {
        store.db.update("DELETE FROM market_control_task WHERE tenant_id=2 AND symbol_id=98004");
        store.db.update("DELETE FROM trading_symbol WHERE id=98004");
        store.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(98004,2)");
        TenantContext.clear();
        TradingSymbol other;
        try(TenantContext.Scope ignored=TenantContext.open(2L)) {
            other=new TradingSymbol();other.setTenantId(2L);other.setId(98004L);other.setSymbol("B");other.setAlltickSymbol("S1");
            other.setCategory("Metal");other.setSourceCategory("Metal");other.setQuoteCurrency("USD");other.setPricePrecision(8);other.setIsEnabled(true);
            when(repository.findAllByTenantId(2L)).thenReturn(Collections.singletonList(other));
            when(repository.findByTenantIdAndId(2L,98004L)).thenReturn(Optional.of(other));market.refreshSymbols();
            long now=System.currentTimeMillis();market.acceptQuote("S1","Metal",quote(now,100,"b-"+UUID.randomUUID()),"http",now);
            market.completeControls();
        }
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        List<Double> times=new ArrayList<>();
        try {
            try(TenantContext.Scope ignored=TenantContext.open(2L)){long at=System.nanoTime();market.controlStatus(98004L);times.add((System.nanoTime()-at)/1e6);}
            Future<?> holder=workers.submit(() -> {try(TenantContext.Scope ignored=TenantContext.open(1L)) {
                store.locked(98001,()->{locked.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;});
            }});
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            CountDownLatch entering=new CountDownLatch(1);
            // S2 status is a pure read. The contending operation must be an explicit engine writer.
            Future<?> blocked=workers.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(1L)){entering.countDown();market.completeControls();}});
            assertTrue(entering.await(2,TimeUnit.SECONDS));Thread.sleep(100);assertFalse(blocked.isDone());
            if(mysql) assertTrue(store.db.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_lock_waits",Integer.class)>0,"physical MySQL row-lock wait is present");
            try(TenantContext.Scope ignored=TenantContext.open(1L)) {
                long at=System.nanoTime();market.controlStatus(98001L);
                assertTrue((System.nanoTime()-at)/1e6<1000,"same-tenant status remains a pure read while its writer is blocked");
            }
            try(TenantContext.Scope ignored=TenantContext.open(2L)) {
                long at=System.nanoTime();market.controlStatus(98004L);assertEquals(100.0,((Number)market.snapshotPrice("B").get("price")).doubleValue());
                String key="b-"+UUID.randomUUID();Map<String,Object> receipt=market.startControl(98004L,60,new BigDecimal("100.054"),10,false,key);
                market.completeControls();receipt=market.controlStatus(98004L);assertNotNull(receipt.get("taskId"));
                assertEquals(receipt.get("taskId"),market.startControl(98004L,60,new BigDecimal("100.054"),10,false,key).get("taskId"));
                times.add((System.nanoTime()-at)/1e6);
            }
            assertFalse(blocked.isDone(),"B operations completed while A still held its own row");
            release.countDown();holder.get(5,TimeUnit.SECONDS);blocked.get(5,TimeUnit.SECONDS);
            try(TenantContext.Scope ignored=TenantContext.open(2L)){long at=System.nanoTime();market.controlStatus(98004L);times.add((System.nanoTime()-at)/1e6);}
            System.out.println("S1_AB normal-status / fault-status+quote+new-and-replayed-receipt / recovery-status ms="+times);
        } finally { release.countDown();workers.shutdownNow();TenantContext.open(1L); }
    }
    @Test void realHttpPreparationThroughMarketJobBorrowsNoTransactionConnection() throws Exception {
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/quote",exchange->{try{Thread.sleep(120);byte[] body="100".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.start();AtomicBoolean called=new AtomicBoolean();
        MarketQuoteSource source=mock(MarketQuoteSource.class);
        when(source.getBatchPrices(anyList(),eq("Metal"))).thenAnswer(i->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(0,borrowed.get());
            java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+server.getAddress().getPort()+"/quote").openConnection();
            connection.setConnectTimeout(1000);connection.setReadTimeout(2000);
            try(java.io.InputStream input=connection.getInputStream()){assertEquals("100",new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}finally{connection.disconnect();}
            assertEquals(0,borrowed.get());called.set(true);long now=System.currentTimeMillis();return Collections.singletonMap("S1",quote(now,100,"http"));
        });
        ReflectionTestUtils.setField(market,"source",source);ReflectionTestUtils.setField(market,"http",mock(MarketHttp.class));
        Object group=((Map<?,?>)ReflectionTestUtils.getField((Object)ReflectionTestUtils.invokeMethod(market,"state"),"groups")).get("Metal");
        try {
            ReflectionTestUtils.invokeMethod(market,"tenantJob",1L,(Runnable)()->ReflectionTestUtils.invokeMethod(market,"tick",group));
            assertTrue(called.get());assertEquals(1,commits.get());assertEquals(0,borrowed.get());
        } finally {server.stop(0);}
    }
}
