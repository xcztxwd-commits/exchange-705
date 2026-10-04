package com.gtcfesk.exchange.market;

import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.Test;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class DepthStreamTest {
    @ServerEndpoint("/depth") public static class Upstream {
        static final Set<Session> sessions=ConcurrentHashMap.newKeySet();
        static final AtomicInteger connections=new AtomicInteger();
        static final List<String> requests=new CopyOnWriteArrayList<>();
        @OnOpen public void open(Session s){sessions.add(s);connections.incrementAndGet();}
        @OnClose public void close(Session s){sessions.remove(s);}
        @OnMessage public void message(Session s,String request)throws Exception{
            requests.add(request);if("ping".equals(request)){s.getBasicRemote().sendText("pong");return;}
            if(request.contains("SET_PROPERTY")){s.getBasicRemote().sendText("{\"result\":null,\"id\":1}");return;}
            if(request.contains("subscribe")||request.contains("SUBSCRIBE")){
                boolean okx=request.contains("books5"),swap=request.contains("SWAP")||request.contains("500ms");
                String symbol=okx?(request.contains("ETH")?"ETH-USDT":"BTC-USDT")+(swap?"-SWAP":""):request.contains("ethusdt@")?"ETHUSDT":"BTCUSDT";
                s.getBasicRemote().sendText(payload(okx,swap,symbol,100,false));
            }
        }
    }
    static String payload(boolean okx,boolean swap,String symbol,int sequence,boolean replacement){
        String bids=replacement?"[[\"98\",\"1\"]]":"[[\"100\",\"1\"],[\"99\",\"2\"]]",asks="[[\"101\",\"3\"]]";
        long now=System.currentTimeMillis();
        if(okx)return "{\"arg\":{\"channel\":\"books5\",\"instId\":\""+symbol+"\"},\"data\":[{\"seqId\":"+sequence+",\"ts\":\""+now+"\",\"bids\":"+bids+",\"asks\":"+asks+"}]}";
        return "{\"stream\":\""+symbol.toLowerCase(Locale.ROOT)+"@depth20"+(swap?"@500ms":"")+"\",\"data\":{"+(swap?"\"s\":\""+symbol+"\",\"u\":"+sequence+",\"T\":"+now+",\"b\":"+bids+",\"a\":"+asks:"\"lastUpdateId\":"+sequence+",\"bids\":"+bids+",\"asks\":"+asks)+"}}";
    }
    static void until(BooleanSupplier c)throws Exception{long end=System.currentTimeMillis()+7000;while(!c.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(15);assertTrue(c.getAsBoolean(),"socket state timed out");}
    static ExchangeDepthSource.Spec spec(boolean okx,String type,String symbol){return new ExchangeDepthSource.Spec(symbol,type,okx&&"swap".equals(type)?"CONTRACT":"BASE_ASSET","BTC","USDT",null,null,null);}
    static void broadcast(String text)throws Exception{for(Session s:new ArrayList<>(Upstream.sessions))if(s.isOpen())s.getBasicRemote().sendText(text);}
    @Test void bothProvidersAndMarketsReplaceResyncSwitchReconnectAndRelease()throws Exception{
        Tomcat server=new Tomcat();server.setBaseDir(Files.createTempDirectory("depth-ws-fixture").toString());server.setPort(0);server.getConnector();
        org.apache.catalina.Context context=server.addContext("",System.getProperty("java.io.tmpdir"));
        Tomcat.addServlet(context,"default",new org.apache.catalina.servlets.DefaultServlet());context.addServletMappingDecoded("/","default");context.addServletContainerInitializer(new WsSci(),Collections.singleton(Upstream.class));
        try{server.start();String url="ws://127.0.0.1:"+server.getConnector().getLocalPort()+"/depth";
            for(boolean okx:Arrays.asList(false,true))for(String type:Arrays.asList("spot","swap")){
                ExchangeDepthStream stream=new ExchangeDepthStream();stream.source=new ExchangeDepthSource();stream.source.exchange=new ExchangeQuoteSource();stream.source.exchange.provider=okx?"okx":"binance";
                stream.spotUrl=okx?"ws://127.0.0.1:1":url;stream.futuresUrl=okx?"ws://127.0.0.1:1":url;stream.okxUrl=okx?url:"ws://127.0.0.1:1";
                String symbol=okx?"BTC-USDT"+("swap".equals(type)?"-SWAP":""):"BTCUSDT";ExchangeDepthSource.Spec first=spec(okx,type,symbol);ExchangeDepthStream.Lane lane=stream.lanes.get(type);
                try{
                    stream.subscriptions(Collections.singleton(first));lane.tick();
                    try{until(()->stream.latest(first)!=null);}catch(AssertionError failure){throw new AssertionError("provider="+stream.source.exchange.provider+" type="+type+" state="+stream.status(type)+" requests="+Upstream.requests,failure);}
                    assertTrue(stream.connected(type));assertFalse(stream.connected("spot".equals(type)?"swap":"spot"));
                    stream.subscriptions(Collections.singleton(first));assertTrue(stream.connected(type),"unchanged demand must not reset in-flight handshake or socket");
                    broadcast(payload(okx,"swap".equals(type),symbol,500,true));until(()->"500".equals(stream.latest(first).sequence));assertEquals(1,stream.latest(first).bids.size());assertEquals("98",DepthBook.text(stream.latest(first).bids.get(0)[0]));
                    // Full snapshots legitimately skip sequence IDs; same ID with different contents must resync.
                    broadcast(payload(okx,"swap".equals(type),symbol,500,false));until(()->stream.latest(first)==null);assertEquals(1L,lane.resyncs.get());assertFalse(stream.connected(type));
                    lane.retryAt=0;lane.tick();until(()->stream.latest(first)!=null);assertTrue(lane.reconnects.get()>=2);
                    if(!okx){broadcast(payload(false,"swap".equals(type),symbol,9,false));until(()->stream.latest(first)==null);assertTrue(lane.resyncs.get()>=2);lane.retryAt=0;lane.tick();until(()->stream.latest(first)!=null);}
                    String nextSymbol=symbol.replace("BTC","ETH");ExchangeDepthSource.Spec next=spec(okx,type,nextSymbol);stream.subscriptions(Collections.singleton(next));assertNull(stream.latest(first));lane.retryAt=0;lane.tick();until(()->stream.latest(next)!=null);
                    long count=lane.messages.get();broadcast(payload(okx,"swap".equals(type),symbol,999,false));Thread.sleep(60);assertEquals(count,lane.messages.get(),"old target must never pollute the new target");assertNull(stream.latest(first));
                    lane.lastFrame=System.currentTimeMillis()-20000;lane.lastPing=System.currentTimeMillis();lane.lastPong=lane.lastPing;lane.tick();assertFalse(stream.connected(type));assertEquals("snapshot_timeout",lane.error);
                    lane.retryAt=0;lane.tick();until(()->stream.latest(next)!=null);
                    for(Session session:new ArrayList<>(Upstream.sessions))session.close();until(()->!stream.connected(type));lane.retryAt=0;lane.tick();until(()->stream.latest(next)!=null);
                    stream.subscriptions(Collections.emptyList());assertFalse(stream.connected(type));assertNull(stream.latest(next));assertEquals(0,stream.status(type).get("subscriptions"));
                }finally{stream.stop();}
            }
        }finally{server.stop();server.destroy();}
    }
    @Test void disabledNeverConnects(){ExchangeDepthStream stream=new ExchangeDepthStream();stream.source=new ExchangeDepthSource();stream.source.exchange=new ExchangeQuoteSource();stream.enabled=false;
        try{stream.subscriptions(Collections.singleton(spec(false,"spot","BTCUSDT")));stream.lanes.get("spot").tick();assertEquals(0L,stream.status("spot").get("reconnects"));assertFalse(stream.connected("spot"));}finally{stream.stop();}}
}
