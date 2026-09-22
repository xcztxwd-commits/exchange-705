package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.market.yahoo.YahooPricing.PricingData;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class YahooConnectionTest {
    @ServerEndpoint("/stream")
    public static class Upstream {
        static final Set<Session> sessions = ConcurrentHashMap.newKeySet();
        static final AtomicInteger connections = new AtomicInteger();
        @OnOpen public void open(Session session) { sessions.add(session); connections.incrementAndGet(); }
        @OnClose public void close(Session session) { sessions.remove(session); }
        @OnMessage public void receive(Session session, String message) throws Exception {
            if (!message.contains("subscribe") || message.contains("unsubscribe")) return;
            byte[] data = PricingData.newBuilder().setId("EURUSD=X").setPrice(1.1f).setTime(System.currentTimeMillis()).build().toByteArray();
            session.getBasicRemote().sendText("{\"type\":\"pricing\",\"message\":\"" + Base64.getEncoder().encodeToString(data) + "\"}");
        }
    }
    static void until(BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "stream did not reach expected state");
    }
    @Test void connectionSubscribesReconnectsAndShadowNeverPublishes() throws Exception {
        Tomcat server = new Tomcat(); server.setBaseDir(Files.createTempDirectory("yahoo-ws-test").toString()); server.setPort(0);
        server.getConnector();
        org.apache.catalina.Context context = server.addContext("", System.getProperty("java.io.tmpdir"));
        Tomcat.addServlet(context, "default", new org.apache.catalina.servlets.DefaultServlet());
        context.addServletMappingDecoded("/", "default");
        context.addServletContainerInitializer(new WsSci(), Collections.singleton(Upstream.class));
        YahooQuoteStream stream = new YahooQuoteStream(); AtomicInteger published = new AtomicInteger();
        try {
            server.start();
            ReflectionTestUtils.setField(stream, "url", "ws://127.0.0.1:" + server.getConnector().getLocalPort() + "/stream");
            ReflectionTestUtils.setField(stream, "mode", "shadow");
            ReflectionTestUtils.setField(stream, "enabledSymbols", "EURUSD=X");
            stream.subscriptions(Collections.singleton("EURUSD=X"), (symbol, quote) -> published.incrementAndGet());
            stream.start(); until(() -> ((Number) stream.status().get("messages")).longValue() >= 1);
            assertEquals(0, published.get());
            int connections = Upstream.connections.get();
            for (Session session : new ArrayList<>(Upstream.sessions)) session.close();
            until(() -> Upstream.connections.get() > connections && ((Number) stream.status().get("messages")).longValue() >= 2);
            assertEquals(0, published.get()); assertTrue(stream.connected());
        } finally { stream.stop(); server.stop(); server.destroy(); }
    }
}
