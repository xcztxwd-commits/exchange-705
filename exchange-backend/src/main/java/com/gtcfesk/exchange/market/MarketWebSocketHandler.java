package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.control.Tenant;
import com.gtcfesk.exchange.control.TenantRepository;
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
    @Autowired private TenantRepository tenants;
    @Autowired(required = false) private MarketDepthService depth;
    @Value("${market.push.interval-ms:1000}") private long intervalMs = 1000;
    @Value("${market.push.delta:false}") private boolean delta;
    private final ObjectMapper mapper = new ObjectMapper();
    private static class Client {
        final Long tenantId;
        final String frontendHost;
        Client(Long tenantId, String frontendHost) { this.tenantId = tenantId; this.frontendHost = frontendHost; }
        final Set<String> symbols = ConcurrentHashMap.newKeySet();
        final Set<String> fastSymbols = ConcurrentHashMap.newKeySet();
        final AtomicBoolean sending = new AtomicBoolean();
        final Map<String,Object> versions = new ConcurrentHashMap<>();
        volatile boolean snapshot = true;
        volatile long busySince;
        volatile long lastListPush;
        volatile String depthSymbol, depthMarketType;
        volatile int depthLevels = 20;
        volatile long depthRevision;
        volatile boolean depthDisabledSent;
        volatile long lastDepthPush;
    }
    private final Map<WebSocketSession, Client> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-push"));
    private final ThreadPoolExecutor senders = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(128), r -> new Thread(r, "market-send"), new ThreadPoolExecutor.AbortPolicy());
    @PostConstruct public void init() { scheduler.scheduleWithFixedDelay(this::push, 0, Math.max(250, intervalMs), TimeUnit.MILLISECONDS); }
    @PreDestroy public void destroy() { scheduler.shutdownNow(); senders.shutdownNow(); }
    @Override public void afterConnectionEstablished(WebSocketSession session) {
        Object tenant = session.getAttributes().get("tenantId"), host = session.getAttributes().get("frontendHost");
        if (!(tenant instanceof Number) || ((Number)tenant).longValue() <= 0 || !(host instanceof String)) {
            close(session); return;
        }
        Client client = new Client(((Number)tenant).longValue(), (String)host);
        if (!validBinding(client)) { close(session); return; }
        if (session instanceof org.springframework.web.socket.adapter.standard.StandardWebSocketSession) {
            javax.websocket.Session nativeSession = ((org.springframework.web.socket.adapter.standard.StandardWebSocketSession) session).getNativeSession();
            nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 1000L);
        }
        clients.put(session, client);
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { clients.remove(session); releaseDepth(session); }
    @Override public void handleTransportError(WebSocketSession session, Throwable error) { clients.remove(session); releaseDepth(session); }
    private void releaseDepth(WebSocketSession session) { if (depth != null) depth.release("ws:" + session.getId()); }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Client client = clients.get(session);
        if (client == null || message.getPayloadLength() > 16384) return;
        if (!validBinding(client)) { close(session); return; }
        try (TenantContext.Scope scope = TenantContext.open(client.tenantId)) {
        Map<String, Object> request = mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() {});
        Object action = request.get("action");
        if ("ping".equals(action)) { send(session, client, Collections.singletonMap("type", "pong")); return; }
        if ("subscribeDepth".equals(action) || "unsubscribeDepth".equals(action)) {
            if (depth == null) return;
            synchronized (client) {
            client.depthRevision++; client.depthDisabledSent = false;
            client.lastDepthPush = 0;
            client.depthSymbol = null; releaseDepth(session);
            if ("subscribeDepth".equals(action)) {
                try {
                    Object symbol = request.get("symbol"), type = request.get("marketType"), levels = request.get("levels");
                    if (!(symbol instanceof String) || type != null && !(type instanceof String)
                            || levels != null && (!(levels instanceof Integer) || (Integer) levels < 1 || (Integer) levels > 20))
                        throw new IllegalArgumentException("Invalid depth subscription");
                    int count = levels == null ? 20 : (Integer) levels;
                    Map<String,Object> initial = depth.read((String) symbol, count, (String) type, "ws:" + session.getId());
                    if ("subscription_limit".equals(initial.get("reason"))) {
                        Map<String,Object> reply = new HashMap<>(); reply.put("type", "depthError"); reply.put("reason", "subscription_limit"); send(session, client, reply); return;
                    }
                    client.depthLevels = count; client.depthMarketType = (String) type; client.depthSymbol = (String) symbol;
                } catch (IllegalArgumentException invalid) {
                    Map<String,Object> reply = new HashMap<>(); reply.put("type", "depthError"); reply.put("reason", "invalid_parameters"); send(session, client, reply);
                }
            }
            }
            return;
        }
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
    }
    private boolean validBinding(Client client) {
        try {
            Tenant tenant = tenants.findById(client.tenantId).orElse(null);
            return tenant != null && client.frontendHost.equals(tenant.getFrontendHost()) && tenant.isDomainVerified()
                && Arrays.asList("ACTIVE", "STOP_NEW").contains(tenant.getStatus());
        } catch (RuntimeException failure) { return false; }
    }
    private void close(WebSocketSession session) {
        clients.remove(session); releaseDepth(session);
        try { session.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) { }
    }
    private static final class PushFrame {
        final WebSocketSession session; final Client client; final Map<String,Object> depthMessage;
        final boolean snapshot, listFrame; final List<String> symbols = new ArrayList<>();
        PushFrame(WebSocketSession session, Client client, Map<String,Object> depthMessage) {
            this.session = session; this.client = client; this.depthMessage = depthMessage;
            snapshot = client.snapshot; listFrame = snapshot || System.currentTimeMillis() - client.lastListPush >= 1000;
            for (String symbol : new ArrayList<>(client.symbols))
                if (listFrame || client.fastSymbols.contains(symbol)) symbols.add(symbol);
        }
    }
    void push() {
        // Each tenant must own a fresh outer snapshot, never join another caller's cross-tenant transaction.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return;
        Map<String,Boolean> bindings = new HashMap<>();
        Map<Long,List<Map.Entry<WebSocketSession,Client>>> groups = new LinkedHashMap<>();
        for (Map.Entry<WebSocketSession, Client> entry : clients.entrySet()) {
            WebSocketSession session = entry.getKey(); Client client = entry.getValue();
            if (!session.isOpen()) { clients.remove(session); releaseDepth(session); continue; }
            if (!bindings.computeIfAbsent(client.tenantId + ":" + client.frontendHost, id -> validBinding(client))) { close(session); continue; }
            groups.computeIfAbsent(client.tenantId, ignored -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<Long,List<Map.Entry<WebSocketSession,Client>>> group : groups.entrySet()) {
            List<PushFrame> frames = new ArrayList<>(); Set<String> symbols = new LinkedHashSet<>(); Map<String,Object> captured;
            try (TenantContext.Scope scope = TenantContext.open(group.getKey())) {
                for (Map.Entry<WebSocketSession,Client> entry : group.getValue()) {
                    WebSocketSession session = entry.getKey(); Client client = entry.getValue(); Map<String,Object> depthMessage = null;
                    synchronized (client) {
                    String depthSymbol = client.depthSymbol;
                    if (depth != null && depthSymbol != null) {
                        try {
                            long revision = client.depthRevision;
                            Map<String,Object> data = depth.read(depthSymbol, client.depthLevels, client.depthMarketType, "ws:" + session.getId());
                            boolean disabled = "DISABLED".equals(data.get("status"));
                            if ((!disabled || !client.depthDisabledSent) && System.currentTimeMillis() - client.lastDepthPush >= 1000) {
                                Map<String,Object> message = new HashMap<>(); message.put("type", "depth"); message.put("data", data);
                                message.put("serverTime", System.currentTimeMillis()); message.put("depthRevision", revision);
                                depthMessage = message;
                            }
                            if (!disabled) client.depthDisabledSent = false;
                        } catch (RuntimeException failure) { releaseDepth(session); }
                    }
                    }
                    PushFrame frame = new PushFrame(session, client, depthMessage); frames.add(frame); symbols.addAll(frame.symbols);
                }
                try {
                    captured = symbols.isEmpty() ? Collections.emptyMap() : marketService.readSnapshot(() -> {
                        Map<String,Object> prices = new HashMap<>();
                        for (String symbol : symbols) prices.put(symbol, Objects.requireNonNull(marketService.snapshotPrice(symbol), "Missing price snapshot"));
                        return prices;
                    });
                } catch (RuntimeException failure) { continue; }
            }
            // Share only this committed tenant batch; all transport work starts after its physical RR transaction ends.
            if (captured == null) continue;
            for (PushFrame frame : frames) {
                Client client = frame.client; Map<String,Object> prices = new HashMap<>();
                for (String symbol : frame.symbols) {
                    Map<?,?> quote = (Map<?,?>) captured.get(symbol);
                    if (!delta || frame.snapshot || !Objects.equals(client.versions.get(symbol), quote.get("quoteVersion"))) prices.put(symbol, quote);
                }
                if (prices.isEmpty()) { if (frame.depthMessage != null) send(frame.session, client, frame.depthMessage); continue; }
                Map<String,Object> message = new HashMap<>(); message.put("type", "price"); message.put("data", prices);
                message.put("snapshot", frame.snapshot); message.put("serverTime", System.currentTimeMillis()); message.put("listFrame", frame.listFrame);
                // One bounded send job carries both independent protocols; neither starves the other.
                List<Map<String,?>> messages = new ArrayList<>();
                if (frame.depthMessage != null) messages.add(frame.depthMessage);
                messages.add(message); send(frame.session, client, messages);
            }
        }
    }
    private void send(WebSocketSession session, Client client, Map<String, ?> message) {
        send(session, client, Collections.singletonList(message));
    }
    private void send(WebSocketSession session, Client client, List<Map<String,?>> messages) {
        if (!client.sending.compareAndSet(false, true)) {
            if (System.currentTimeMillis() - client.busySince > 10000) {
                clients.remove(session); releaseDepth(session); try { session.close(CloseStatus.SESSION_NOT_RELIABLE); } catch (Exception ignored) { }
            }
            return;
        }
        client.busySince = System.currentTimeMillis();
        try {
            senders.execute(() -> {
                try { if (session.isOpen() && validBinding(client)) {
                    for (Map<String,?> message : messages) {
                    if ("depth".equals(message.get("type")) && (!Objects.equals(message.get("depthRevision"), client.depthRevision)
                            || client.depthSymbol == null || !client.depthSymbol.equals(((Map<?,?>) message.get("data")).get("symbol")))) continue;
                    Map<String,Object> frame = new HashMap<>(message); frame.remove("depthRevision");
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
                    if ("depth".equals(message.get("type"))) {
                        client.lastDepthPush = System.currentTimeMillis();
                        client.depthDisabledSent = "DISABLED".equals(((Map<?,?>) message.get("data")).get("status"));
                    }
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
                    }
                } }
                catch (Exception failure) { clients.remove(session); releaseDepth(session); }
                finally { client.sending.set(false); }
            });
        } catch (RejectedExecutionException full) { client.sending.set(false); }
    }
}

