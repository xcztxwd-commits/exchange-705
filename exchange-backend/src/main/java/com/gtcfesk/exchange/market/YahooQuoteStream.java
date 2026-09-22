package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.market.yahoo.YahooPricing.PricingData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/** One upstream connection. Shadow observations never enter the authoritative quote/history path. */
@Component
public class YahooQuoteStream {
    @Value("${market.yahoo.mode:http_only}") private String mode = "http_only";
    @Value("${market.yahoo.url:wss://streamer.finance.yahoo.com/?version=2}") private String url;
    @Value("${market.yahoo.enabled-symbols:}") private String enabledSymbols = "";
    @Value("${market.yahoo.stable-ms:30000}") private long stableMs = 30000;
    @Value("${market.yahoo.silence-ms:10000}") private long silenceMs = 10000;
    private static final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService lifecycle = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "yahoo-connection"));
    private final ThreadPoolExecutor receiver = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1024), r -> new Thread(r, "yahoo-quotes"), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Map<String,Object>> observations = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong(), messages = new AtomicLong(), rejected = new AtomicLong(), gaps = new AtomicLong();
    private volatile Set<String> desired = Collections.emptySet();
    private Set<String> subscribed = new HashSet<>();
    private volatile WebSocketSession session;
    private volatile BiConsumer<String, Map<String,Object>> consumer;
    private volatile long connectedAt, lastFrame, nextConnect, lastSubscribe, lastPong, lastPing;
    private volatile long lastSummary;
    private volatile String error;
    private volatile boolean stopped;
    private int failures;

    @PostConstruct public void start() {
        if (!Arrays.asList("http_only", "shadow", "ws_preferred").contains(mode)) throw new IllegalArgumentException("Invalid market.yahoo.mode");
        lifecycle.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
    }
    public void subscriptions(Set<String> symbols, BiConsumer<String, Map<String,Object>> callback) {
        desired = Collections.unmodifiableSet(new HashSet<>(symbols)); consumer = callback;
        observations.keySet().retainAll(symbols);
    }
    boolean enabled(String symbol) {
        return "ws_preferred".equals(mode) && Arrays.asList(enabledSymbols.split("\\s*,\\s*")).contains(symbol);
    }
    public boolean healthy(String symbol) {
        Map<String,Object> quote = observations.get(symbol);
        long now = System.currentTimeMillis();
        return enabled(symbol) && connected() && error == null && now - connectedAt >= stableMs && quote != null
            && now - QuoteState.time(quote.get("timestamp")) < silenceMs
            && now - QuoteState.time(quote.get("fetchedAt")) < silenceMs;
    }
    public boolean connected() { WebSocketSession current = session; return current != null && current.isOpen(); }

    private void tick() {
        if (stopped || "http_only".equals(mode) || desired.isEmpty()) { disconnect(); return; }
        long now = System.currentTimeMillis();
        try {
            if (!connected()) {
                if (now < nextConnect) return;
                final long connection = generation.incrementAndGet();
                StandardWebSocketClient client = new StandardWebSocketClient();
                client.setUserProperties(Collections.singletonMap("org.apache.tomcat.websocket.IO_TIMEOUT_MS", "5000"));
                WebSocketSession opened = client.doHandshake(new TextWebSocketHandler() {
                    @Override public void afterConnectionEstablished(WebSocketSession s) { s.setTextMessageSizeLimit(65536); }
                    @Override protected void handlePongMessage(WebSocketSession s, PongMessage message) { if (connection == generation.get()) lastPong = System.currentTimeMillis(); }
                    @Override protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                        if (connection != generation.get() || stopped) return;
                        lastFrame = System.currentTimeMillis();
                        final long receivedAt = lastFrame;
                        try {
                            Map<String,Object> quote = decode(message.getPayload());
                            if (quote == null || !desired.contains(quote.get("symbol"))) return;
                            quote.put("fetchedAt", receivedAt); quote.put("eventId", UUID.randomUUID().toString());
                            receiver.execute(() -> {
                                if (connection != generation.get() || stopped) return;
                                try {
                                    observe(quote);
                                    BiConsumer<String,Map<String,Object>> callback = consumer;
                                    if (enabled((String) quote.get("symbol")) && callback != null) callback.accept((String) quote.get("symbol"), quote);
                                } catch (Exception failure) { failed("processing_failure"); }
                            });
                        } catch (RejectedExecutionException full) { gaps.incrementAndGet(); failed("queue_overflow"); }
                        catch (Exception invalid) { rejected.incrementAndGet(); }
                    }
                    @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus status) { if (connection == generation.get()) failed("disconnected"); }
                    @Override public void handleTransportError(WebSocketSession s, Throwable failure) { if (connection == generation.get()) failed("transport_failure"); }
                }, url).get(6, TimeUnit.SECONDS);
                if (connection != generation.get() || stopped) { opened.close(); return; }
                session = opened; connectedAt = System.currentTimeMillis(); lastPong = connectedAt; lastPing = 0;
                error = null; subscribed.clear(); lastSubscribe = 0;
            }
            if (!subscribed.equals(desired) || now - lastSubscribe >= 15000) {
                Set<String> removed = new HashSet<>(subscribed); removed.removeAll(desired);
                if (!removed.isEmpty()) session.sendMessage(new TextMessage(json.writeValueAsString(Collections.singletonMap("unsubscribe", removed))));
                session.sendMessage(new TextMessage(json.writeValueAsString(Collections.singletonMap("subscribe", desired))));
                subscribed = new HashSet<>(desired); lastSubscribe = now;
            }
            if (lastPing > lastPong && now - lastPing > 10000) { failed("heartbeat_timeout"); return; }
            if (now - lastPing >= 30000) { session.sendMessage(new PingMessage()); lastPing = now; }
            if (now - connectedAt >= 30000) failures = 0;
            if (now - lastSummary >= 60000) {
                lastSummary = now;
                org.slf4j.LoggerFactory.getLogger(YahooQuoteStream.class).info("Yahoo stream mode={} subscribed={} observed={} messages={} rejected={} gaps={} queue={}",
                    mode, desired.size(), observations.size(), messages.get(), rejected.get(), gaps.get(), receiver.getQueue().size());
            }
        } catch (Exception failure) { failed("connection_failure"); }
    }
    static Map<String,Object> decode(String payload) throws Exception {
        if (payload.length() > 65536) throw new IllegalArgumentException("frame_too_large");
        JsonNode frame = json.readTree(payload);
        if (!"pricing".equals(frame.path("type").asText())) return null;
        PricingData data = PricingData.parseFrom(Base64.getDecoder().decode(frame.path("message").asText()));
        Map<String,Object> quote = new HashMap<>();
        quote.put("symbol", data.getId()); quote.put("price", Double.parseDouble(Float.toString(data.getPrice())));
        long time = data.getTime(); quote.put("timestamp", time < 10000000000L ? time * 1000 : time);
        if (!QuoteState.valid(quote) || data.getId().isEmpty()) throw new IllegalArgumentException("invalid_quote");
        if (data.hasChange()) quote.put("change24h", (double) data.getChange());
        if (data.hasChangePercent()) quote.put("changePct24h", (double) data.getChangePercent());
        if (data.hasPreviousClose() && data.getPreviousClose() > 0) quote.put("previousClose", (double) data.getPreviousClose());
        quote.put("changeBasis", "previousClose"); quote.put("marketState", "unknown");
        quote.put("marketHours", data.getMarketHours()); quote.put("transport", "ws");
        return quote;
    }
    void observe(Map<String,Object> quote) {
        messages.incrementAndGet();
        observations.compute((String) quote.get("symbol"), (symbol, old) -> {
            Map<String,Object> observation = new HashMap<>(old != null
                && QuoteState.time(old.get("timestamp")) > QuoteState.time(quote.get("timestamp")) ? old : quote);
            long lag = QuoteState.time(quote.get("fetchedAt")) - QuoteState.time(quote.get("timestamp"));
            observation.put("messageCount", (old == null ? 0 : QuoteState.time(old.get("messageCount"))) + 1);
            observation.put("freshSamples", (old == null ? 0 : QuoteState.time(old.get("freshSamples"))) + (lag >= -5000 && lag < 15000 ? 1 : 0));
            observation.put("maxSourceLagMs", Math.max(lag, old == null ? 0 : QuoteState.time(old.get("maxSourceLagMs"))));
            observation.put("lastQueueLagMs", System.currentTimeMillis() - QuoteState.time(quote.get("fetchedAt")));
            return observation;
        });
    }
    private synchronized void failed(String reason) {
        if (stopped) return;
        error = reason; failures = Math.min(10, failures + 1);
        nextConnect = System.currentTimeMillis() + Math.min(30000, 1000L << Math.min(5, failures - 1)) + ThreadLocalRandom.current().nextInt(500);
        disconnect();
    }
    private synchronized void disconnect() {
        generation.incrementAndGet(); WebSocketSession old = session; session = null;
        gaps.addAndGet(receiver.getQueue().size()); receiver.getQueue().clear();
        if (old != null) try { old.close(); } catch (Exception ignored) { }
    }
    public Map<String,Object> status() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("mode", mode); result.put("enabledSymbols", enabledSymbols); result.put("connected", connected());
        result.put("connectedAt", connectedAt); result.put("lastFrame", lastFrame); result.put("retryAt", nextConnect);
        result.put("error", error); result.put("messages", messages.get()); result.put("rejected", rejected.get());
        result.put("gaps", gaps.get()); result.put("queueDepth", receiver.getQueue().size()); result.put("subscriptions", desired);
        result.put("observations", new TreeMap<>(observations)); return result;
    }
    @PreDestroy public void stop() { stopped = true; disconnect(); lifecycle.shutdownNow(); receiver.shutdownNow(); }
}
