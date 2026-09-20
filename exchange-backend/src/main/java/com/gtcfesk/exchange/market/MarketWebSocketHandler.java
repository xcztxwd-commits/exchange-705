package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ObjectMapper mapper = new ObjectMapper();
    private static class Client {
        final Set<String> symbols = ConcurrentHashMap.newKeySet();
        final AtomicBoolean sending = new AtomicBoolean();
    }
    private final Map<WebSocketSession, Client> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-push"));
    private final ThreadPoolExecutor senders = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(128), r -> new Thread(r, "market-send"), new ThreadPoolExecutor.AbortPolicy());
    @PostConstruct public void init() { scheduler.scheduleAtFixedRate(this::push, 0, 1, TimeUnit.SECONDS); }
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
            if ("subscribe".equals(action) && client.symbols.size() < 512) client.symbols.add(symbol);
            else if ("unsubscribe".equals(action)) client.symbols.remove(symbol);
        }
        if ("subscribe".equals(action)) {
            Map<String, Object> reply = new HashMap<>(); reply.put("type", "subscribed"); reply.put("symbols", new ArrayList<>(client.symbols));
            send(session, client, reply);
        }
    }
    void push() {
        for (Map.Entry<WebSocketSession, Client> entry : clients.entrySet()) {
            WebSocketSession session = entry.getKey(); Client client = entry.getValue();
            if (!session.isOpen()) { clients.remove(session); continue; }
            if (client.symbols.isEmpty()) continue;
            Map<String, Object> prices = new HashMap<>();
            for (String symbol : client.symbols) prices.put(symbol, marketService.internalPrice(symbol));
            Map<String, Object> message = new HashMap<>(); message.put("type", "price"); message.put("data", prices);
            send(session, client, message);
        }
    }
    private void send(WebSocketSession session, Client client, Map<String, ?> message) {
        if (!client.sending.compareAndSet(false, true)) return;
        try {
            senders.execute(() -> {
                try { if (session.isOpen()) session.sendMessage(new TextMessage(mapper.writeValueAsString(message))); }
                catch (Exception failure) { clients.remove(session); }
                finally { client.sending.set(false); }
            });
        } catch (RejectedExecutionException full) { client.sending.set(false); }
    }
}

