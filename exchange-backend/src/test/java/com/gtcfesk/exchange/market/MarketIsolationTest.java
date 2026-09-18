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

/** Runs only in docker/compose.isolation.yaml, against its empty MySQL and Redis. */
@EnabledIfEnvironmentVariable(named = "MARKET_ISOLATION_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketIsolationTest {
    static volatile String metalMode = "ok", allMode = "ok", klineMode = "ok";
    static final AtomicInteger metalCalls = new AtomicInteger();
    static final ObjectMapper json = new ObjectMapper();
    static final ExecutorService mockWorkers = Executors.newFixedThreadPool(12);
    static final HttpServer server;
    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
            server.setExecutor(mockWorkers);
            server.createContext("/", MarketIsolationTest::reply);
            server.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("market.quote.base-url", MarketIsolationTest::base);
        registry.add("market.quote.yahoo-url", MarketIsolationTest::base);
        registry.add("market.quote.alltick-url", MarketIsolationTest::base);
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
    static void reply(HttpExchange exchange) {
        try {
            String uri = exchange.getRequestURI().toString();
            boolean metal = uri.contains("USDT-FUTURES");
            if (metal) metalCalls.incrementAndGet();
            String mode = "ok".equals(allMode) ? metal ? metalMode : "ok" : allMode;
            if ("ok".equals(mode) && uri.contains("candles")) mode = klineMode;
            if ("block".equals(mode)) Thread.sleep(60000);
            int status = "429".equals(mode) ? 429 : "500".equals(mode) ? 500 : 200;
            if (status == 429) exchange.getResponseHeaders().set("Retry-After", "8");
            long now = System.currentTimeMillis();
            String body;
            if ("invalid".equals(mode)) body = "{\"code\":\"00000\",\"data\":[{\"symbol\":\"XAUUSDT\",\"lastPrice\":\"NaN\",\"ts\":" + now + "}]}";
            else if (uri.contains("spark")) body = "{\"spark\":{\"result\":[{\"symbol\":\"EURUSD=X\",\"response\":[{\"meta\":{\"regularMarketPrice\":1.1,\"regularMarketTime\":" + now / 1000 + ",\"previousClose\":1}}]}]}}";
            else if (uri.contains("candles")) body = "{\"code\":\"00000\",\"data\":[[\"" + now + "\",\"100\",\"101\",\"99\",\"100\",\"1\",\"100\"]]}";
            else body = "{\"code\":\"00000\",\"requestTime\":" + now + ",\"data\":[{\"symbol\":\"" + (metal ? "XAUUSDT" : "BTCUSDT") + "\",\"lastPrice\":\"100\",\"ts\":" + now + "}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        } catch (Exception ignored) { } finally { exchange.close(); }
    }
    @Autowired ForexQuoteMarketService quotes;
    @Autowired MarketQuoteSource source;
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
        symbol.setCategory(category); symbol.setName(internal); symbol.setBaseCurrency(internal);
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
        WebSocketSession session = new StandardWebSocketClient().doHandshake(reader, "ws://127.0.0.1:" + port + "/api/ws/market").get(5, TimeUnit.SECONDS);
        session.sendMessage(new TextMessage("{\"action\":\"subscribe\",\"symbols\":[\"XAUUSD\",\"EURUSD\",\"BTCUSD\"]}"));
        return session;
    }
    Map<String, Object> metalState() { return quotes.sourceStatus().stream().filter(x -> "Metal".equals(x.get("category"))).findFirst().get(); }
    void recovery() throws Exception {
        metalMode = "ok"; allMode = "ok";
        until(() -> quotes.freshPrice("XAUUSD") != null && quotes.freshPrice("EURUSD") != null, 36000);
    }
    void bounded() {
        for (Map<String, Object> state : quotes.sourceStatus()) {
            assertTrue(((Number) state.get("threads")).intValue() <= 1);
            assertTrue(((Number) state.get("schedulerQueue")).intValue() <= 1);
            assertTrue(((Number) state.get("pendingKlines")).intValue() <= 32);
        }
    }
    @Test @SuppressWarnings("unchecked") void failureIsolationAndSettlement() throws Exception {
        symbol("BTCUSD", "BTCUSDT", "Crypto"); symbol("XAUUSD", "XAUUSD", "Metal"); symbol("EURUSD", "EURUSD", "Forex");
        symbol("MISSINGFX", "MISSINGFX", "Forex"); // Provider omits this item; EURUSD must still update every cycle.
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
                Map<?, ?> batch = client.postForObject("http://127.0.0.1:" + port + "/api/market/price/batch", Collections.singletonMap("symbols", Arrays.asList("EURUSD", "XAUUSD")), Map.class);
                assertTrue(System.currentTimeMillis() - before < 1000);
                assertEquals("available", ((Map<?, ?>) ((Map<?, ?>) batch.get("data")).get("EURUSD")).get("status"));
                for (int i = 0; i < 100; i++) quotes.getKline("XAUUSD", "1m", i + 1, "Metal");
                bounded();
            }
            assertTrue(updates >= 10);
            assertEquals("unavailable", quotes.internalPrice("MISSINGFX").get("status"));
            assertTrue(metalCalls.get() - initialCalls <= 7, "backoff bounds request count");
            System.out.println("PASS blocked source 60s: healthy updates=" + updates + ", max new push gap=" + maxGap + "ms; threads/queues bounded");
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
            ReflectionTestUtils.setField(source, "baseUrl", "http://127.0.0.1:1");
            until(() -> quotes.freshPrice("XAUUSD") == null && quotes.freshPrice("BTCUSD") == null, 6000);
            assertNotNull(quotes.freshPrice("EURUSD"));
            System.out.println("PASS connection refused leaves Yahoo healthy");
            ReflectionTestUtils.setField(source, "baseUrl", base()); recovery();
            allMode = "500";
            until(() -> quotes.freshPrices().isEmpty(), 6000);
            long timestamp = QuoteState.time(quotes.internalPrice("XAUUSD").get("timestamp"));
            long fetchedAt = QuoteState.time(quotes.internalPrice("XAUUSD").get("fetchedAt"));
            Thread.sleep(16000);
            assertEquals(timestamp, QuoteState.time(quotes.internalPrice("XAUUSD").get("timestamp")));
            assertEquals(fetchedAt, QuoteState.time(quotes.internalPrice("XAUUSD").get("fetchedAt")));
            assertEquals("stale", quotes.internalPrice("XAUUSD").get("status"));
            assertEquals(timestamp, QuoteState.time(redis.getPrice("Metal:XAUUSD").get("timestamp")));
            OptionOrder order = new OptionOrder(); order.setUserId(99001L); order.setSymbol("XAUUSD"); order.setDirection("UP");
            order.setAmount(BigDecimal.TEN); order.setOpenPrice(BigDecimal.ONE); order.setDuration(1); order.setOpenTime(LocalDateTime.now().minusSeconds(30)); order.setStatus("TRADING");
            order = options.save(order);
            optionService.settleExpiredOrders(Collections.singletonMap("XAUUSD", new BigDecimal("999")));
            assertEquals("TRADING", options.findById(order.getId()).get().getStatus());
            System.out.println("PASS all sources failed: retained timestamps and last price; stale option stays TRADING");
            // Recover only the healthy group. A mixed account must not liquidate its healthy losing position.
            allMode = "ok"; metalMode = "500";
            until(() -> quotes.freshPrice("BTCUSD") != null && quotes.freshPrice("EURUSD") != null, 36000);
            AssetAccount account = new AssetAccount(); account.setUserId(99002L); account.setCoin("CONTRACT"); account.setAvailable(BigDecimal.ZERO); account.setFrozen(BigDecimal.TEN); accounts.save(account);
            for (String code : Arrays.asList("BTCUSD", "XAUUSD")) {
                ContractOrder c = new ContractOrder(); c.setUserId(99002L); c.setSymbol(code); c.setSide("BUY"); c.setType("MARKET");
                c.setQuantity(BigDecimal.ONE); c.setOpenPrice(new BigDecimal("1000")); c.setCurrentPrice(BigDecimal.ONE);
                c.setStatus("OPEN"); c.setMargin(BigDecimal.ONE); c.setFee(BigDecimal.ZERO); c.setLeverage(BigDecimal.ONE); contracts.save(c);
            }
            contractService.checkAndForceCloseOrders(quotes.freshPrices());
            assertEquals(2, contracts.findByStatus("OPEN").size());
            assertEquals(0, accounts.findByUserIdAndCoin(99002L, "CONTRACT").get().getFrozen().compareTo(BigDecimal.TEN));
            System.out.println("PASS incomplete account skipped despite stored currentPrice and losing healthy position");
            // Remove only synthetic fixtures before normal recovery resumes automatic settlement.
            contracts.deleteAll(); options.deleteAll(); recovery();
            until(() -> "available".equals(quotes.getKline("XAUUSD", "1m", 1, "Metal").get("status")), 15000);
            Map<String, Object> kline = quotes.internalKline("XAUUSD", "1m", 1);
            ((Map<String, Object>) ((List<?>) ((Map<?, ?>) kline.get("data")).get("kline_list")).get(0)).put("close_price", 999d);
            Map<?, ?> next = quotes.internalKline("XAUUSD", "1m", 1);
            assertEquals(110d, ((Number) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) next.get("data")).get("kline_list")).get(0)).get("close_price")).doubleValue());
            bounded(); System.out.println("PASS recovery and independent K-line copies");
        } finally { one.close(); two.close(); }
    }
    @AfterAll static void stopMock() { server.stop(0); mockWorkers.shutdownNow(); }
}
