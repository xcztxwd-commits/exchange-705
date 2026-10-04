package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.trade.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.junit.jupiter.api.extension.ExtendWith;
import com.gtcfesk.exchange.tenant.BootTenantFixture;
import com.gtcfesk.exchange.tenant.TenantOneFixture;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.client.RestTemplate;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real HTTP/JWT/MySQL/Redis regression, on the identified and independently restored application fixture. */
@EnabledIfEnvironmentVariable(named = "MARKET_ISOLATION_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(BootTenantFixture.class)
@ExtendWith(TenantOneFixture.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MarketIsolationTest {
    static final String MISSING_SYMBOL = "MISSINGFX" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    static volatile boolean catalogTesting;
    @Autowired org.springframework.context.ApplicationContext context;
    static volatile String metalMode = "ok", allMode = "ok", klineMode = "ok";
    static final AtomicInteger metalCalls = new AtomicInteger();
    static final ObjectMapper json = new ObjectMapper();
    static ExecutorService mockWorkers;
    static HttpServer server;
    static synchronized String base() {
        if(server==null)try {
            if(System.getProperty("application.mysql.fixture","").trim().isEmpty())throw new IllegalStateException("MARKET_ISOLATION_TEST requires an explicit certified application.mysql.fixture");
            catalogTesting=false;metalMode=allMode=klineMode="ok";metalCalls.set(0);
            mockWorkers=Executors.newFixedThreadPool(12);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
            server.setExecutor(mockWorkers);
            server.createContext("/", MarketIsolationTest::reply);
            server.start();
        } catch (Exception e) {throw new IllegalStateException("Owned synthetic provider fixture failed",e);}
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if(System.getProperty("application.mysql.fixture","").trim().isEmpty())throw new IllegalStateException("MARKET_ISOLATION_TEST requires an explicit certified application.mysql.fixture");
        String redisPort=System.getenv("MT705_TEST_REDIS_PORT"),redisPassword=System.getenv("MT705_TEST_REDIS_PASSWORD");
        if(redisPort==null||!redisPort.matches("[1-9][0-9]{0,4}")||redisPassword==null||redisPassword.isEmpty())throw new IllegalStateException("Explicit owned Redis port and private password required");
        registry.add("spring.redis.host",()->"127.0.0.1");registry.add("spring.redis.port",()->redisPort);registry.add("spring.redis.password",()->redisPassword);
        registry.add("platform.base-domain",()->"mt705.test");registry.add("platform.admin-origin",()->"https://"+BootTenantFixture.ADMIN);registry.add("platform.control-origin",()->"https://"+BootTenantFixture.CONTROL);registry.add("security.trusted-proxies",()->"127.0.0.1/32,::1/128");
        registry.add("market.depth.enabled",()->"false");registry.add("market.exchange.stream-enabled",()->"false");registry.add("market.yahoo.mode",()->"http_only");
        registry.add("market.exchange.spot-url", MarketIsolationTest::base);
        registry.add("market.exchange.futures-url", MarketIsolationTest::base);
        registry.add("market.quote.yahoo-url", MarketIsolationTest::base);
        registry.add("market.quote.alltick-url", MarketIsolationTest::base);
        registry.add("market.catalog.yahoo-url", MarketIsolationTest::base);
        // This fixture exercises expiry after 16 seconds, independently of the production TTL.
        registry.add("market.quote.max-age-ms", () -> "15000");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");registry.add("spring.sql.init.mode",()->"never");
    }
    static void reply(HttpExchange exchange) {
        if(catalogTesting) {CatalogTradingScenario.reply(exchange); return;}
        try {
            String uri = exchange.getRequestURI().toString();
            boolean metal = uri.contains("/fapi/");
            if (metal) metalCalls.incrementAndGet();
            String mode = "ok".equals(allMode) ? metal ? metalMode : "ok" : allMode;
            if ("ok".equals(mode) && uri.contains("klines")) mode = klineMode;
            if ("block".equals(mode)) Thread.sleep(60000);
            int status = "429".equals(mode) ? 429 : "500".equals(mode) ? 500 : 200;
            if (status == 429) exchange.getResponseHeaders().set("Retry-After", "8");
            long now = System.currentTimeMillis();
            String body;
            if ("invalid".equals(mode)) body = "{\"symbol\":\"XAUUSDT\",\"lastPrice\":\"NaN\",\"closeTime\":" + now + "}";
            else if (uri.contains("spark")) body = "{\"spark\":{\"result\":[{\"symbol\":\"EURUSD=X\",\"response\":[{\"meta\":{\"regularMarketPrice\":1.1,\"regularMarketTime\":" + now / 1000 + ",\"previousClose\":1}}]}]}}";
            else if (uri.contains("klines")) body = "[[\"" + (now / 60000 * 60000) + "\",\"100\",\"101\",\"99\",\"100\",\"1\",\"" + now + "\",\"100\"]]";
            else body = "{\"symbol\":\"" + (metal ? "XAUUSDT" : "BTCUSDT") + "\",\"lastPrice\":\"100\",\"closeTime\":" + now + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        } catch (Exception ignored) { } finally { exchange.close(); }
    }
    @Autowired ForexQuoteMarketService quotes;
    @Autowired MarketQuoteSource source;
    @Autowired ExchangeQuoteSource exchange;
    @Autowired TradingSymbolRepository symbols;
    @Autowired OptionOrderRepository options;
    @Autowired ContractOrderRepository contracts;
    @Autowired AssetAccountRepository accounts;
    @Autowired OptionOrderService optionService;
    @Autowired ContractOrderService contractService;
    @Autowired RedisMarketService redis;
    @LocalServerPort int port;
    static void until(BooleanSupplier condition, long timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), "condition not met within " + timeout + "ms");
    }
    void symbol(String internal, String market, String category) {
        TradingSymbol symbol = new TradingSymbol(); symbol.setSymbol(internal); symbol.setAlltickSymbol(market);
        symbol.setCategory(category); symbol.setSourceCategory(category); symbol.setMarketSource(com.gtcfesk.exchange.market.MarketInstrumentCatalog.inferredSource(category)); symbol.setName(internal); symbol.setBaseCurrency(MISSING_SYMBOL.equals(internal) ? "MISSINGFX" : internal);
        assertTrue(symbol.getBaseCurrency().length() <= 16, "synthetic base currency must fit schema");
        if ("Metal".equals(category)) { symbol.setControlEnabled(true); symbol.setControlPriceOffset(BigDecimal.TEN); }
        symbols.save(symbol);
    }
    static class Reader extends TextWebSocketHandler {
        final BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        @Override @SuppressWarnings("unchecked") protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
            Map<String, Object> value = json.readValue(message.getPayload(), Map.class);
            if ("price".equals(value.get("type"))) messages.add((Map<String, Object>) value.get("data"));
        }
    }
    WebSocketSession connect(Reader reader) throws Exception {
        WebSocketHttpHeaders headers=new WebSocketHttpHeaders();headers.set("X-Forwarded-Host",BootTenantFixture.FRONT);headers.setOrigin("https://"+BootTenantFixture.FRONT);
        WebSocketSession session = new StandardWebSocketClient().doHandshake(reader,headers,URI.create("ws://127.0.0.1:" + port + "/api/ws/market")).get(5, TimeUnit.SECONDS);
        session.sendMessage(new TextMessage("{\"action\":\"subscribe\",\"symbols\":[\"XAUUSD\",\"EURUSD\",\"BTCUSD\"]}"));
        return session;
    }
    Map<String, Object> metalState() { return quotes.sourceStatus().stream().filter(x -> "Metal".equals(x.get("category"))).findFirst().get(); }
    void recovery() throws Exception {
        metalMode = "ok"; allMode = "ok";
        boolean controlEnabled = Boolean.TRUE.equals(symbols.findByTenantIdAndSymbol(1L, "XAUUSD").get().getControlEnabled());
        // Recovery requires fresh provider ingress and its committed producer snapshot, not an old execution lease.
        until(() -> {
            Map<String,Object> raw = quotes.getPrice("XAUUSD", "Metal"), committed = quotes.internalPrice("XAUUSD");
            long sourceTime = QuoteState.time(raw.get("sourceTimestamp")), fetchedAt = QuoteState.time(raw.get("fetchedAt"));
            return Boolean.TRUE.equals(raw.get("available")) && sourceTime > 0 && fetchedAt > 0
                && Boolean.TRUE.equals(committed.get("sourceAvailable"))
                && QuoteState.time(committed.get("sourceTimestamp")) >= sourceTime
                && QuoteState.time(committed.get("fetchedAt")) >= fetchedAt
                && Boolean.valueOf(controlEnabled).equals(committed.get("controlActive"))
                && quotes.freshPrice("XAUUSD") != null && quotes.freshPrice("EURUSD") != null;
        }, 36000);
    }
    void bounded() {
        for (Map<String, Object> state : quotes.sourceStatus()) {
            assertTrue(((Number) state.get("threads")).intValue() <= 1);
            assertTrue(((Number) state.get("schedulerQueue")).intValue() <= 1);
            assertTrue(((Number) state.get("pendingKlines")).intValue() <= 32);
        }
    }
    @Test @Order(1) @SuppressWarnings("unchecked") void failureIsolationAndSettlement() throws Exception {
        // Each JVM uses an omitted code with no historical Redis quote; do not delete shared source evidence.
        Map<String,Object> initialMissingRedis = redis.getPrice("Forex:" + MISSING_SYMBOL), initialMissingRaw = quotes.getPrice(MISSING_SYMBOL, "Forex");
        Map<String,Object> initialMissing = new LinkedHashMap<>();initialMissing.put("symbol",MISSING_SYMBOL);initialMissing.put("redis",initialMissingRedis);initialMissing.put("raw",initialMissingRaw);
        System.out.println("MISSING_SOURCE_INIT " + json.writeValueAsString(initialMissing));
        assertTrue(MISSING_SYMBOL.length() <= 32);
        assertFalse(QuoteState.valid(initialMissingRedis), "omitted fixture must not inherit a valid Redis quote");
        assertFalse(QuoteState.valid(initialMissingRaw), "omitted fixture must start without a valid raw quote");
        assertEquals(0L, QuoteState.time(initialMissingRedis == null ? null : initialMissingRedis.get("timestamp")));
        assertEquals(0L, QuoteState.time(initialMissingRaw.get("sourceTimestamp")));
        symbol("BTCUSD", "BTCUSDT", "Crypto"); symbol("XAUUSD", "XAUUSD", "Metal"); symbol("EURUSD", "EURUSD", "Forex");
        symbol(MISSING_SYMBOL, MISSING_SYMBOL, "Forex"); // Provider omits this item; EURUSD must still update every cycle.
        quotes.refreshSymbols(); recovery();
        Reader first = new Reader(), second = new Reader();
        WebSocketSession one = connect(first), two = connect(second);
        try {
            for (Reader reader : Arrays.asList(first, second)) {
                Map<String, Object> message = reader.messages.poll(6, TimeUnit.SECONDS);
                assertNotNull(message);
                assertEquals(110d, ((Number) ((Map<?, ?>) message.get("XAUUSD")).get("price")).doubleValue());
            }
            assertEquals(100d, ((Number) quotes.getPrice("XAUUSD", "Metal").get("price")).doubleValue());
            System.out.println("PASS multi-client offset is applied exactly once");
            klineMode = "500";
            quotes.getKline("XAUUSD", "5m", 10, "Metal");
            until(() -> ((Number) metalState().get("klineFailures")).intValue() > 0, 6000);
            assertNotNull(quotes.freshPrice("XAUUSD"), "chart failure must not invalidate a healthy ticker");
            klineMode = "ok";
            System.out.println("PASS chart-only failure preserves healthy ticker snapshot");
            metalMode = "block";
            long start = System.currentTimeMillis(), lastNew = start, lastFetched = 0, maxGap = 0;
            int initialCalls = metalCalls.get(), updates = 0;
            first.messages.clear();
            RestTemplate client = new RestTemplate();
            while (System.currentTimeMillis() - start < 60000) {
                Map<String, Object> message = first.messages.poll(6, TimeUnit.SECONDS);
                assertNotNull(message, "healthy push must continue within 6 seconds");
                Map<String, Object> healthy = (Map<String, Object>) message.get("EURUSD");
                long fetched = QuoteState.time(healthy.get("fetchedAt"));
                if (fetched > lastFetched) {
                    long gap = System.currentTimeMillis() - lastNew;
                    assertTrue(gap <= 6000, "healthy new quote gap=" + gap);
                    maxGap = Math.max(maxGap, gap); lastNew = System.currentTimeMillis(); lastFetched = fetched; updates++;
                }
                long before = System.currentTimeMillis();
                HttpHeaders headers=new HttpHeaders();headers.set("X-Forwarded-Host",BootTenantFixture.FRONT);
                Map<?, ?> batch = client.postForObject("http://127.0.0.1:" + port + "/api/market/price/batch",new HttpEntity<>(Collections.singletonMap("symbols", Arrays.asList("EURUSD", "XAUUSD")),headers), Map.class);
                assertTrue(System.currentTimeMillis() - before < 1000);
                assertEquals("available", ((Map<?, ?>) ((Map<?, ?>) batch.get("data")).get("EURUSD")).get("status"));
                for (int i = 0; i < 100; i++) quotes.getKline("XAUUSD", "1m", i + 1, "Metal");
                bounded();
            }
            assertTrue(updates >= 10);
            Map<String,Object> missing=quotes.internalPrice(MISSING_SYMBOL);
            assertEquals("unavailable",missing.get("sourceStatus"));assertEquals("engine_lag",missing.get("status"));
            assertEquals(false,missing.get("available"));assertEquals(false,missing.get("tradeAvailable"));assertEquals(true,missing.get("stale"));assertNull(quotes.freshPrice(MISSING_SYMBOL));
            assertTrue(metalCalls.get() - initialCalls <= 7, "backoff bounds request count");
            System.out.println("PASS blocked source 60s: healthy updates=" + updates + ", max new push gap=" + maxGap + "ms; threads/queues bounded");
            // A configured control has an execution lease during outages. Test raw-source
            // fail-closed settlement separately, without changing that production contract.
            assertFalse(Boolean.TRUE.equals(quotes.getPrice("XAUUSD", "Metal").get("available")));
            assertNotNull(quotes.freshPrice("XAUUSD"), "active control retains its execution lease");
            TradingSymbol metal = symbols.findByTenantIdAndSymbol(1L, "XAUUSD").get();
            metal.setControlEnabled(false); symbols.save(metal); quotes.refreshSymbols();
            recovery();
            for (String mode : Arrays.asList("500", "invalid", "429")) {
                int previous = metalCalls.get(); metalMode = mode;
                until(() -> metalCalls.get() > previous && quotes.freshPrice("XAUUSD") == null, 6000);
                assertNotNull(quotes.freshPrice("EURUSD"));
                if ("429".equals(mode)) {
                    int calls = metalCalls.get(); Thread.sleep(6500); assertEquals(calls, metalCalls.get(), "Retry-After must suppress quotes and K-lines");
                }
                System.out.println("PASS " + mode + " isolation: " + metalState()); recovery();
            }
            exchange.spotUrl = exchange.futuresUrl = "http://127.0.0.1:1";
            until(() -> quotes.freshPrice("XAUUSD") == null && quotes.freshPrice("BTCUSD") == null, 6000);
            assertNotNull(quotes.freshPrice("EURUSD"));
            System.out.println("PASS connection refused leaves Yahoo healthy");
            exchange.spotUrl = exchange.futuresUrl = base(); recovery();
            allMode = "500";
            until(() -> quotes.freshPrices().isEmpty(), 6000);
            long timestamp = QuoteState.time(quotes.internalPrice("XAUUSD").get("timestamp"));
            long fetchedAt = QuoteState.time(quotes.internalPrice("XAUUSD").get("fetchedAt"));
            Thread.sleep(16000);
            assertEquals(timestamp, QuoteState.time(quotes.internalPrice("XAUUSD").get("timestamp")));
            assertEquals(fetchedAt, QuoteState.time(quotes.internalPrice("XAUUSD").get("fetchedAt")));
            Map<String,Object> stale=quotes.internalPrice("XAUUSD");
            assertEquals("stale",stale.get("sourceStatus"));assertEquals("engine_lag",stale.get("status"));
            assertEquals(false,stale.get("available"));assertEquals(false,stale.get("tradeAvailable"));assertEquals(true,stale.get("stale"));assertNull(quotes.freshPrice("XAUUSD"));
            assertEquals(timestamp, QuoteState.time(redis.getPrice("Metal:XAUUSD").get("timestamp")));
            // A real eligible account ensures this assertion reaches stale-quote rejection, not a missing-user/funds error.
            UserAccount staleUser=new UserAccount();staleUser.setEmail("stale-option-"+UUID.randomUUID()+"@example.invalid");staleUser.setPasswordHash(context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class).encode(UUID.randomUUID().toString()));staleUser=context.getBean(UserAccountRepository.class).saveAndFlush(staleUser);
            KycRecord staleIdentity=new KycRecord();staleIdentity.setUserId(staleUser.getId());staleIdentity.setRealName("Synthetic stale option");staleIdentity.setIdNumber("TEST-ONLY");staleIdentity.setStatus("APPROVED");context.getBean(KycRecordRepository.class).saveAndFlush(staleIdentity);
            AssetAccount staleFunds=new AssetAccount();staleFunds.setUserId(staleUser.getId());staleFunds.setCoin("OPTION");staleFunds.setAvailable(BigDecimal.ZERO);staleFunds.setFrozen(BigDecimal.TEN);staleFunds=accounts.saveAndFlush(staleFunds);
            OptionOrder order = new OptionOrder(); order.setUserId(staleUser.getId()); order.setSymbol("XAUUSD"); order.setDirection("UP");
            order.setAmount(BigDecimal.TEN); order.setOpenPrice(BigDecimal.ONE); order.setDuration(1); order.setOpenTime(LocalDateTime.now().minusSeconds(30)); order.setStatus("TRADING");
            order = options.save(order);
            Long staleUserId=staleUser.getId(),staleOrderId=order.getId();long staleVersion=order.getRowVersion(),staleFundsVersion=staleFunds.getRowVersion();
            com.gtcfesk.exchange.common.BusinessException quoteFailure=assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->optionService.closeOrder(staleUserId,staleOrderId,new BigDecimal("999")));
            assertEquals("行情暂不可用或报价已过期，暂缓结算",quoteFailure.getMessage());
            optionService.settleExpiredOrders(Collections.singletonMap("XAUUSD", new BigDecimal("999")));
            assertEquals("TRADING", options.findByTenantIdAndId(1L, order.getId()).get().getStatus());
            assertEquals(staleVersion,options.findByTenantIdAndId(1L,staleOrderId).get().getRowVersion());AssetAccount unchanged=accounts.findByTenantIdAndUserIdAndCoin(1L,staleUserId,"OPTION").get();assertEquals(staleFundsVersion,unchanged.getRowVersion());assertEquals(0,unchanged.getAvailable().compareTo(BigDecimal.ZERO));assertEquals(0,unchanged.getFrozen().compareTo(BigDecimal.TEN));
            System.out.println("PASS all sources failed: retained timestamps and last price; stale option stays TRADING");
            // Recover only the healthy group. A mixed account must not liquidate its healthy losing position.
            allMode = "ok"; metalMode = "500";
            until(() -> quotes.freshPrice("BTCUSD") != null && quotes.freshPrice("EURUSD") != null, 36000);
            UserAccount mixedUser=new UserAccount();mixedUser.setEmail("mixed-account-"+UUID.randomUUID()+"@example.invalid");mixedUser.setPasswordHash("NOT_A_LOGIN_PASSWORD");mixedUser=context.getBean(UserAccountRepository.class).saveAndFlush(mixedUser);Long mixedUserId=mixedUser.getId();
            AssetAccount account = new AssetAccount(); account.setUserId(mixedUserId); account.setCoin("CONTRACT"); account.setAvailable(BigDecimal.ZERO); account.setFrozen(BigDecimal.TEN); account=accounts.saveAndFlush(account);long mixedFundsVersion=account.getRowVersion();Map<Long,Long> mixedOrderVersions=new HashMap<>();
            for (String code : Arrays.asList("BTCUSD", "XAUUSD")) {
                ContractOrder c = new ContractOrder(); c.setUserId(mixedUserId); c.setSymbol(code); c.setSide("BUY"); c.setType("MARKET");
                c.setQuantity(BigDecimal.ONE); c.setOpenPrice(new BigDecimal("1000")); c.setCurrentPrice(BigDecimal.ONE);
                c.setStatus("OPEN"); c.setMargin(BigDecimal.ONE); c.setFee(BigDecimal.ZERO); c.setLeverage(BigDecimal.ONE); c=contracts.saveAndFlush(c);mixedOrderVersions.put(c.getId(),c.getRowVersion());
            }
            contractService.checkAndForceCloseOrders(quotes.freshPrices());
            assertEquals(2, contracts.findByTenantIdAndStatus(1L, "OPEN").size());
            AssetAccount mixedUnchanged=accounts.findByTenantIdAndUserIdAndCoin(1L,mixedUserId,"CONTRACT").get();assertEquals(0,mixedUnchanged.getFrozen().compareTo(BigDecimal.TEN));assertEquals(0,mixedUnchanged.getAvailable().compareTo(BigDecimal.ZERO));assertEquals(mixedFundsVersion,mixedUnchanged.getRowVersion());
            for(Map.Entry<Long,Long> initial:mixedOrderVersions.entrySet()){ContractOrder unchangedOrder=contracts.findByTenantIdAndId(1L,initial.getKey()).get();assertEquals("OPEN",unchangedOrder.getStatus());assertEquals(initial.getValue().longValue(),unchangedOrder.getRowVersion());}
            System.out.println("PASS incomplete account skipped despite stored currentPrice and losing healthy position");
            // Remove only synthetic fixtures before normal recovery resumes automatic settlement.
            contracts.deleteAllByTenantId(1L); options.deleteAllByTenantId(1L); recovery();
            metal = symbols.findByTenantIdAndSymbol(1L, "XAUUSD").get();
            metal.setControlEnabled(true); symbols.save(metal); quotes.refreshSymbols();
            // Cached K-lines do not prove that the producer has committed the re-enabled execution price.
            until(() -> {
                Map<String,Object> committed = quotes.internalPrice("XAUUSD");
                return Boolean.TRUE.equals(committed.get("controlActive")) && Boolean.TRUE.equals(committed.get("available"))
                    && committed.get("price") instanceof Number && Double.compare(((Number)committed.get("price")).doubleValue(), 110d) == 0
                    && "available".equals(quotes.getKline("XAUUSD", "1m", 1, "Metal").get("status"));
            }, 15000);
            Map<String, Object> kline = quotes.internalKline("XAUUSD", "1m", 1);
            ((Map<String, Object>) ((List<?>) ((Map<?, ?>) kline.get("data")).get("kline_list")).get(0)).put("close_price", 999d);
            Map<?, ?> next = quotes.internalKline("XAUUSD", "1m", 1);
            assertEquals(100d, ((Number) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) next.get("data")).get("kline_list")).get(0)).get("close_price")).doubleValue(),
                    "today's offset must not rewrite source history without recorded control points");
            assertEquals(110d, quotes.freshPrice("XAUUSD").doubleValue(), "current controlled execution price is independent of source history");
            bounded(); System.out.println("PASS recovery and independent K-line copies");
        } finally { one.close(); two.close(); }
    }
    @Test @Order(2) void catalogTradingChain() throws Exception {
        catalogTesting=true; new CatalogTradingScenario(this).run();
    }
    @AfterAll static synchronized void stopMock() {if(server!=null){server.stop(0);server=null;}if(mockWorkers!=null){mockWorkers.shutdownNow();mockWorkers=null;}}
}
