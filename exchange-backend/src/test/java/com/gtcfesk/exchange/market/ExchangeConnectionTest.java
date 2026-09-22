package com.gtcfesk.exchange.market;

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

class ExchangeConnectionTest {
    @ServerEndpoint("/stream") public static class Upstream {
        static final Set<Session> sessions=ConcurrentHashMap.newKeySet();
        static final AtomicInteger connections=new AtomicInteger(),textPings=new AtomicInteger();
        @OnOpen public void open(Session session){sessions.add(session);connections.incrementAndGet();}
        @OnClose public void close(Session session){sessions.remove(session);}
        @OnMessage public void receive(Session session,String message)throws Exception{
            if("ping".equals(message)){textPings.incrementAndGet();session.getBasicRemote().sendText("pong");return;}
            long now=System.currentTimeMillis();
            if(message.contains("SUBSCRIBE"))session.getBasicRemote().sendText("{\"e\":\"24hrTicker\",\"s\":\"BTCUSDT\",\"c\":\"100\",\"o\":\"90\",\"C\":"+now+"}");
            else if(message.contains("subscribe"))session.getBasicRemote().sendText("{\"arg\":{\"channel\":\"tickers\"},\"data\":[{\"instId\":\"BTC-USDT\",\"last\":\"100\",\"open24h\":\"90\",\"ts\":\""+now+"\"}]}");
        }
    }
    static void until(BooleanSupplier condition)throws Exception{
        long end=System.currentTimeMillis()+10000;while(!condition.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(25);
        assertTrue(condition.getAsBoolean(),"stream state timed out");
    }
    @Test void bothProtocolsRecoverAndOnlySelectedExchangeConnects()throws Exception{
        Tomcat server=new Tomcat();server.setBaseDir(Files.createTempDirectory("exchange-ws-test").toString());server.setPort(0);server.getConnector();
        org.apache.catalina.Context context=server.addContext("",System.getProperty("java.io.tmpdir"));
        Tomcat.addServlet(context,"default",new org.apache.catalina.servlets.DefaultServlet());context.addServletMappingDecoded("/","default");
        context.addServletContainerInitializer(new WsSci(),Collections.singleton(Upstream.class));
        try{server.start();String url="ws://127.0.0.1:"+server.getConnector().getLocalPort()+"/stream";
            for(String provider:Arrays.asList("binance","okx")){
                ExchangeQuoteStream stream=new ExchangeQuoteStream();stream.source=new ExchangeQuoteSource();stream.source.provider=provider;
                stream.spotUrl="binance".equals(provider)?url:"ws://127.0.0.1:1";
                stream.okxUrl="okx".equals(provider)?url:"ws://127.0.0.1:1";
                AtomicInteger received=new AtomicInteger();
                try{
                    stream.subscriptions("Crypto",Collections.singleton("BTCUSDT"),(code,quote)->received.incrementAndGet());stream.start();
                    until(()->received.get()>0);assertTrue(stream.connected("Crypto"));assertFalse(stream.healthy("Crypto","BTCUSDT"));
                    Map<?,?> lanes=(Map<?,?>)ReflectionTestUtils.getField(stream,"lanes");Object lane=lanes.get("Crypto");
                    ReflectionTestUtils.setField(lane,"connectedAt",System.currentTimeMillis()-31000);
                    assertTrue(stream.healthy("Crypto","BTCUSDT"));assertFalse(stream.connected("Metal"));
                    final int before=received.get();for(Session session:new ArrayList<>(Upstream.sessions))session.close();
                    until(()->!stream.connected("Crypto"));assertFalse(stream.healthy("Crypto","BTCUSDT"));
                    until(()->received.get()>before);assertTrue(stream.connected("Crypto"));
                    // Exercise the same rotation branch as the 23-hour production limit without waiting a day.
                    final int previous=received.get();ReflectionTestUtils.setField(lane,"connectedAt",System.currentTimeMillis()-stream.rotationMs-1000);
                    until(()->received.get()>previous);
                    if("okx".equals(provider)){int pings=Upstream.textPings.get();ReflectionTestUtils.setField(lane,"lastPing",0L);until(()->Upstream.textPings.get()>pings);}
                    // Force a missing-pong deadline and prove recovery.
                    final int last=received.get();ReflectionTestUtils.setField(lane,"lastPong",System.currentTimeMillis()-30000);
                    ReflectionTestUtils.setField(lane,"lastPing",System.currentTimeMillis()-11000);until(()->received.get()>last);
                }finally{stream.stop();}
            }
        }finally{server.stop();server.destroy();}
    }
    @Test void cryptoPerpetualUsesFuturesSocketAndPublishesPerpetualMetadata()throws Exception{
        Tomcat server=new Tomcat();server.setBaseDir(Files.createTempDirectory("perpetual-ws-test").toString());server.setPort(0);server.getConnector();
        org.apache.catalina.Context context=server.addContext("",System.getProperty("java.io.tmpdir"));
        Tomcat.addServlet(context,"default",new org.apache.catalina.servlets.DefaultServlet());context.addServletMappingDecoded("/","default");
        context.addServletContainerInitializer(new WsSci(),Collections.singleton(Upstream.class));
        ExchangeQuoteStream stream=new ExchangeQuoteStream();stream.source=new ExchangeQuoteSource();
        try{
            server.start();stream.futuresUrl="ws://127.0.0.1:"+server.getConnector().getLocalPort()+"/stream";
            stream.spotUrl=stream.okxUrl="ws://127.0.0.1:1";
            java.util.concurrent.atomic.AtomicReference<Map<String,Object>> received=new java.util.concurrent.atomic.AtomicReference<>();
            stream.subscriptions("CryptoPerpetual",Collections.singleton("BTCUSDT"),(code,quote)->received.set(quote));stream.start();
            until(()->received.get()!=null);
            assertEquals("perpetual",received.get().get("marketType"));assertEquals("Binance",received.get().get("source"));
            assertTrue(stream.connected("CryptoPerpetual"));assertFalse(stream.connected("Crypto"));assertFalse(stream.connected("Metal"));
        }finally{stream.stop();server.stop();server.destroy();}
    }
    @Test void disabledStreamNeverConnects()throws Exception{
        ExchangeQuoteStream stream=new ExchangeQuoteStream();stream.source=new ExchangeQuoteSource();stream.enabled=false;
        try{stream.subscriptions("Crypto",Collections.singleton("BTCUSDT"),(s,q)->fail("disabled source published"));
            Map<?,?> lanes=(Map<?,?>)ReflectionTestUtils.getField(stream,"lanes");ReflectionTestUtils.invokeMethod(lanes.get("Crypto"),"tick");
            assertFalse(stream.connected("Crypto"));assertEquals(0L,stream.status("Crypto").get("reconnects"));
        }finally{stream.stop();}
    }
}
