package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import javax.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/** Independent bounded category lanes. The configured secondary exchange does not connect until selected. */
@Component
public class ExchangeQuoteStream {
    @Autowired ExchangeQuoteSource source;
    @Value("${market.exchange.stream-enabled:true}") boolean enabled = true;
    @Value("${market.exchange.spot-ws:wss://data-stream.binance.vision/ws}") String spotUrl = "wss://data-stream.binance.vision/ws";
    @Value("${market.exchange.futures-ws:wss://fstream.binance.com/market/ws}") String futuresUrl = "wss://fstream.binance.com/market/ws";
    @Value("${market.exchange.okx-ws:wss://ws.okx.com:8443/ws/v5/public}") String okxUrl = "wss://ws.okx.com:8443/ws/v5/public";
    @Value("${market.exchange.rotation-ms:82800000}") long rotationMs = 82800000; // Reconnect before Binance's 24-hour limit.
    private final Map<String,Lane> lanes = new LinkedHashMap<>();
    public ExchangeQuoteStream() { lanes.put("Crypto",new Lane("Crypto")); lanes.put("Metal",new Lane("Metal")); lanes.put("CryptoPerpetual",new Lane("CryptoPerpetual")); }
    @PostConstruct void start() { for (Lane lane : lanes.values()) lane.timer.scheduleWithFixedDelay(lane::tick,0,1,TimeUnit.SECONDS); }
    void subscriptions(String category, Set<String> codes, BiConsumer<String,Map<String,Object>> callback) {
        Lane lane = lanes.get(category); if (lane == null) return;
        Map<String,String> mapped = new TreeMap<>();
        for (String code : codes) try { mapped.put(code, ExchangeQuoteSource.symbol(code, category, "okx".equals(source.provider))); }
            catch (MarketHttp.Failure ignored) { }
        lane.consumer = callback; lane.desired = Collections.unmodifiableMap(mapped);
        lane.latest.keySet().retainAll(mapped.keySet());
    }
    boolean connected(String category) { Lane lane = lanes.get(category); return lane != null && lane.connected(); }
    boolean healthy(String category, String code) {
        Lane lane = lanes.get(category); if (lane == null || !lane.connected() || lane.error != null) return false;
        Map<String,Object> quote = lane.latest.get(code); long now = System.currentTimeMillis();
        return now - lane.connectedAt >= 30000 && QuoteState.valid(quote)
            && now - QuoteState.time(quote.get("timestamp")) < 10000 && now - QuoteState.time(quote.get("fetchedAt")) < 10000;
    }
    Map<String,Object> status(String category) {
        Lane lane = lanes.get(category); Map<String,Object> result = new LinkedHashMap<>();
        result.put("enabled",enabled); result.put("activeProvider",source.name()); result.put("automaticSecondary",false);
        result.put("connected",lane.connected()); result.put("connectedAt",lane.connectedAt); result.put("retryAt",lane.retryAt);
        result.put("error",lane.error); result.put("subscriptions",lane.desired); result.put("messages",lane.messages.get());
        result.put("rejected",lane.rejected.get()); result.put("gaps",lane.gaps.get()); result.put("queueDepth",lane.worker.getQueue().size());
        result.put("reconnects",lane.reconnects.get()); result.put("lastFrame",lane.lastFrame); return result;
    }
    @PreDestroy public void stop() { for (Lane lane:lanes.values()) { lane.stopped=true; lane.disconnect(); lane.timer.shutdownNow(); lane.worker.shutdownNow(); } }
    private class Lane {
        final String category;
        final ScheduledExecutorService timer;
        final ThreadPoolExecutor worker;
        final AtomicLong generation=new AtomicLong(),messages=new AtomicLong(),rejected=new AtomicLong(),gaps=new AtomicLong(),reconnects=new AtomicLong();
        final Map<String,Map<String,Object>> latest=new ConcurrentHashMap<>();
        volatile Map<String,String> desired=Collections.emptyMap();
        Map<String,String> subscribed=Collections.emptyMap();
        volatile BiConsumer<String,Map<String,Object>> consumer;
        volatile WebSocketSession session;
        volatile long connectedAt,lastFrame,lastPing,lastPong,retryAt,subscribeAt;
        volatile boolean acknowledged,stopped;
        volatile String error;
        int failures;
        Lane(String category) {
            this.category=category;
            timer=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"exchange-connect-"+category));
            worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1024),r->new Thread(r,"exchange-quote-"+category),new ThreadPoolExecutor.AbortPolicy());
        }
        boolean connected() { WebSocketSession current=session; return current!=null && current.isOpen(); }
        void tick() {
            if (stopped || !enabled || desired.isEmpty()) { disconnect(); return; }
            long now=System.currentTimeMillis(); boolean okx="okx".equals(source.provider);
            try {
                if (connected() && (!subscribed.equals(desired) || now-connectedAt>=rotationMs)) { fail("subscription_or_rotation"); return; }
                if (!connected()) {
                    if (now<retryAt) return;
                    final long connection=generation.incrementAndGet();
                    StandardWebSocketClient client=new StandardWebSocketClient();
                    client.setUserProperties(Collections.singletonMap("org.apache.tomcat.websocket.IO_TIMEOUT_MS","5000"));
                    String url=okx?okxUrl:ExchangeQuoteSource.perpetual(category)?futuresUrl:spotUrl;
                    WebSocketSession opened=client.doHandshake(new TextWebSocketHandler() {
                        @Override public void afterConnectionEstablished(WebSocketSession s) throws Exception {
                            if (connection!=generation.get() || stopped) { s.close(); return; }
                            s.setTextMessageSizeLimit(65536);
                        }
                        @Override protected void handlePongMessage(WebSocketSession s,PongMessage message) { if(connection==generation.get()) lastPong=System.currentTimeMillis(); }
                        // Tomcat JSR-356 automatically echoes incoming PING payload in PONG, including Binance server pings.
                        @Override protected void handleTextMessage(WebSocketSession s,TextMessage message) {
                            if(connection!=generation.get() || stopped) return;
                            long received=System.currentTimeMillis(); lastFrame=received;
                            if(okx && "pong".equals(message.getPayload())) {lastPong=received;return;}
                            try {
                                JsonNode root=ExchangeQuoteSource.JSON.readTree(message.getPayload());
                                if(root.has("code") || "error".equals(root.path("event").asText())) {fail("subscription_error");return;}
                                if(root.has("result") || "subscribe".equals(root.path("event").asText())) {acknowledged=true;return;}
                                JsonNode data=okx?root.path("data").path(0):root.has("data")?root.path("data"):root;
                                if("serverShutdown".equals(data.path("e").asText())) {fail("server_shutdown");return;}
                                if(okx ? !"tickers".equals(root.path("arg").path("channel").asText()) : !"24hrTicker".equals(data.path("e").asText())) return;
                                Map<String,Object> quote=ExchangeQuoteSource.ticker(data,okx,true);
                                quote.put("source",source.name()); quote.put("fetchedAt",received);quote.put("transport","ws");
                                quote.put("marketType",ExchangeQuoteSource.perpetual(category)?"perpetual":"spot");quote.put("quoteCurrency","USDT");
                                quote.put("eventId",UUID.randomUUID().toString());
                                if(!desired.containsValue(quote.get("symbol")))return;
                                acknowledged=true;
                                worker.execute(()->{
                                    if(connection!=generation.get() || stopped)return;
                                    try {
                                        for(Map.Entry<String,String> pair:desired.entrySet()) if(pair.getValue().equals(quote.get("symbol"))) {
                                            consumer.accept(pair.getKey(),quote);
                                            latest.compute(pair.getKey(),(key,old)->old!=null && QuoteState.time(old.get("timestamp"))>QuoteState.time(quote.get("timestamp"))?old:quote);
                                        }
                                        messages.incrementAndGet();
                                    } catch(Exception failure){fail("processing_failure");}
                                });
                            } catch(RejectedExecutionException full){gaps.incrementAndGet();fail("queue_overflow");}
                            catch(Exception invalid){rejected.incrementAndGet();}
                        }
                        @Override public void afterConnectionClosed(WebSocketSession s,CloseStatus status){if(connection==generation.get())fail("disconnected");}
                        @Override public void handleTransportError(WebSocketSession s,Throwable cause){if(connection==generation.get())fail("transport_failure");}
                    },url).get(6,TimeUnit.SECONDS);
                    if(connection!=generation.get() || stopped){opened.close();return;}
                    session=opened; connectedAt=lastFrame=lastPong=System.currentTimeMillis(); lastPing=0; acknowledged=false;
                    subscribed=new TreeMap<>(desired); latest.clear(); error=null; reconnects.incrementAndGet();
                    Map<String,Object> request=new LinkedHashMap<>();
                    if(okx){ List<Map<String,String>> args=new ArrayList<>();for(String symbol:new HashSet<>(subscribed.values())){
                        Map<String,String> arg=new HashMap<>();arg.put("channel","tickers");arg.put("instId",symbol);args.add(arg);
                    } request.put("op","subscribe");request.put("args",args);
                    } else {List<String> params=new ArrayList<>();for(String symbol:new HashSet<>(subscribed.values()))params.add(symbol.toLowerCase(Locale.ROOT)+"@ticker");
                        request.put("method","SUBSCRIBE");request.put("params",params);request.put("id",connection);}
                    if(subscribed.size()>512) {fail("subscription_limit");return;}
                    session.sendMessage(new TextMessage(ExchangeQuoteSource.JSON.writeValueAsString(request)));subscribeAt=System.currentTimeMillis();
                    return;
                }
                if(!acknowledged && now-subscribeAt>10000){fail("subscription_timeout");return;}
                if(lastPing>lastPong && now-lastPing>10000){fail("heartbeat_timeout");return;}
                if(now-lastPing>=20000){session.sendMessage(okx?new TextMessage("ping"):new PingMessage());lastPing=now;}
                if(now-lastFrame>60000){fail("stream_silence");return;}
                if(now-connectedAt>=30000)failures=0;
            }catch(Exception failure){fail("connection_failure");}
        }
        synchronized void fail(String reason){if(stopped)return;error=reason;failures=Math.min(10,failures+1);
            retryAt=System.currentTimeMillis()+Math.min(30000,1000L<<Math.min(5,failures-1))+ThreadLocalRandom.current().nextInt(500);disconnect();}
        synchronized void disconnect(){generation.incrementAndGet();WebSocketSession old=session;session=null;
            gaps.addAndGet(worker.getQueue().size());worker.getQueue().clear();latest.clear();
            if(old!=null)try{old.close();}catch(Exception ignored){} }
    }
}
