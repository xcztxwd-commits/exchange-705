package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Push only snapshots. Each client has at most one send in progress; slow clients never queue work. */
@Component
public class MarketWebSocketHandler extends TextWebSocketHandler {
    @Autowired private ForexQuoteMarketService marketService;
    @Value("${market.push.interval-ms:1000}") private long intervalMs = 1000;
    @Value("${market.push.delta:false}") private boolean delta;
    private final ObjectMapper mapper = new ObjectMapper();
    private static class Client {
        final Set<String> symbols = ConcurrentHashMap.newKeySet();
        final Set<String> fastSymbols = ConcurrentHashMap.newKeySet();
        final AtomicBoolean sending = new AtomicBoolean();
        final Map<String,Object> versions = new ConcurrentHashMap<>();
        volatile boolean snapshot = true;
        volatile long busySince;
        volatile long lastListPush;
    }
    private final Map<WebSocketSession, Client> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-push"));
    private final ThreadPoolExecutor senders = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(128), r -> new Thread(r, "market-send"), new ThreadPoolExecutor.AbortPolicy());
    @PostConstruct public void init() { scheduler.scheduleWithFixedDelay(this::push, 0, Math.max(250, intervalMs), TimeUnit.MILLISECONDS); }
    @PreDestroy public void destroy() { scheduler.shutdownNow(); senders.shutdownNow(); }
    @Override public void afterConnectionEstablished(WebSocketSession session) {
        if (session instanceof org.springframework.web.socket.adapter.standard.StandardWebSocketSession) {
            javax.websocket.Session nativeSession = ((org.springframework.web.socket.adapter.standard.StandardWebSocketSession) session).getNativeSession();
            nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 1000L);
        }
        clients.put(session, new Client());
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { clients.remove(session); }
    @Override public void handleTransportError(WebSocketSession session, Throwable error) { clients.remove(session); }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Client client = clients.get(session);
        if (client == null || message.getPayloadLength() > 16384) return;
        Map<String, Object> request = mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() {});
        Object action = request.get("action");
        if ("ping".equals(action)) { send(session, client, Collections.singletonMap("type", "pong")); return; }
        Object symbols = request.get("symbols");
        if (!(symbols instanceof List)) return;
        for (Object value : (List<?>) symbols) {
            if (!(value instanceof String)) continue;
            String symbol = (String) value;
            if ("subscribe".equals(action) && client.symbols.size() < 512 && marketService.knownSymbol(symbol)) {
                client.symbols.add(symbol); client.versions.remove(symbol); client.snapshot = true;
            } else if ("unsubscribe".equals(action)) { client.symbols.remove(symbol); client.versions.remove(symbol); }
        }
        if ("subscribe".equals(action)) {
            if (request.get("fastSymbols") instanceof List) {
                client.fastSymbols.clear();
                for (Object symbol : (List<?>) request.get("fastSymbols"))
                    if (symbol instanceof String && client.symbols.contains(symbol)) client.fastSymbols.add((String) symbol);
            }
            Map<String, Object> reply = new HashMap<>(); reply.put("type", "subscribed"); reply.put("symbols", new ArrayList<>(client.symbols));
            send(session, client, reply);
            scheduler.execute(this::push);
        }
    }
    void push() {
        // Compute a symbol only once per cycle, independent of client count.
        Map<String,Map<String,Object>> snapshots = new HashMap<>();
        for (Map.Entry<WebSocketSession, Client> entry : clients.entrySet()) {
            WebSocketSession session = entry.getKey(); Client client = entry.getValue();
            if (!session.isOpen()) { clients.remove(session); continue; }
            if (client.symbols.isEmpty()) continue;
            Map<String, Object> prices = new HashMap<>();
            boolean listFrame = client.snapshot || System.currentTimeMillis() - client.lastListPush >= 1000;
            for (String symbol : client.symbols) {
                if (!listFrame && !client.fastSymbols.contains(symbol)) continue;
                Map<String,Object> quote;
                try { quote = snapshots.computeIfAbsent(symbol, marketService::snapshotPrice); }
                catch (Exception failure) { continue; }
                if (!delta || client.snapshot || !Objects.equals(client.versions.get(symbol), quote.get("quoteVersion"))) prices.put(symbol, quote);
            }
            if (prices.isEmpty()) continue;
            Map<String, Object> message = new HashMap<>(); message.put("type", "price"); message.put("data", prices);
            message.put("snapshot", client.snapshot); message.put("serverTime", System.currentTimeMillis());
            message.put("listFrame", listFrame);
            send(session, client, message);
        }
    }
    private void send(WebSocketSession session, Client client, Map<String, ?> message) {
        if (!client.sending.compareAndSet(false, true)) {
            if (System.currentTimeMillis() - client.busySince > 10000) {
                clients.remove(session); try { session.close(CloseStatus.SESSION_NOT_RELIABLE); } catch (Exception ignored) { }
            }
            return;
        }
        client.busySince = System.currentTimeMillis();
        try {
            senders.execute(() -> {
                try { if (session.isOpen()) {
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
                    if ("price".equals(message.get("type"))) {
                        Map<?,?> prices = (Map<?,?>) message.get("data");
                        prices.forEach((symbol, quote) -> {
                            Object version = ((Map<?,?>) quote).get("quoteVersion");
                            if (version != null && client.symbols.contains(symbol)) client.versions.put((String) symbol, version);
                        });
                        // A subscription added while sending must still receive its own snapshot.
                        client.snapshot = !client.versions.keySet().containsAll(client.symbols);
                        if (Boolean.TRUE.equals(message.get("listFrame"))) client.lastListPush = System.currentTimeMillis();
                    }
                } }
                catch (Exception failure) { clients.remove(session); }
                finally { client.sending.set(false); }
            });
        } catch (RejectedExecutionException full) { client.sending.set(false); }
    }
}

