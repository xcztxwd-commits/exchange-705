package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import javax.annotation.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Two bounded multiplexed snapshot lanes. No unbounded message queues or incremental book state. */
@Component
class ExchangeDepthStream {
    @Autowired ExchangeDepthSource source;
    @Value("${market.exchange.stream-enabled:true}") boolean enabled=true;
    @Value("${market.exchange.spot-ws:wss://data-stream.binance.vision/ws}") String spotUrl="wss://data-stream.binance.vision/ws";
    @Value("${market.depth.futures-ws:wss://fstream.binance.com/public/ws}") String futuresUrl="wss://fstream.binance.com/public/ws";
    @Value("${market.depth.okx-ws:wss://ws.okx.com/ws/v5/public}") String okxUrl="wss://ws.okx.com/ws/v5/public";
    @Value("${market.depth.max-age-ms:15000}") long maxAgeMs=15000;
    @Value("${market.exchange.rotation-ms:82800000}") long rotationMs=82800000;
    final Map<String,Lane> lanes=new LinkedHashMap<>();
    ExchangeDepthStream(){lanes.put("spot",new Lane("spot"));lanes.put("swap",new Lane("swap"));}
    @PostConstruct void start(){
        if(!source.enabled)return;
        if(source.okx())source.official(okxUrl,"wss",Collections.singleton("ws.okx.com"));
        else {source.official(spotUrl,"wss",Arrays.asList("data-stream.binance.vision","stream.binance.com"));source.official(futuresUrl,"wss",Collections.singleton("fstream.binance.com"));}
        for(Lane lane:lanes.values())lane.timer.scheduleWithFixedDelay(lane::tick,0,1000,TimeUnit.MILLISECONDS);
    }
    synchronized void subscriptions(Collection<ExchangeDepthSource.Spec> specs){
        for(Lane lane:lanes.values()){
            Map<String,ExchangeDepthSource.Spec> next=new TreeMap<>();
            for(ExchangeDepthSource.Spec spec:specs)if(lane.type.equals(spec.marketType))next.put(spec.externalSymbol,spec);
            Set<String> previous=new HashSet<>(lane.desired.keySet());
            lane.desired=Collections.unmodifiableMap(next);
            lane.latest.keySet().retainAll(next.keySet());
            // Release immediately when the final subscriber leaves; reconnect on a changed set.
            if(!previous.equals(next.keySet()))lane.disconnect();
        }
    }
    DepthBook latest(ExchangeDepthSource.Spec spec){return lanes.get(spec.marketType).latest.get(spec.externalSymbol);}
    boolean connected(String type){return lanes.get(type).connected();}
    Map<String,Object> status(String type){Lane l=lanes.get(type);Map<String,Object> r=new LinkedHashMap<>();
        r.put("connected",l.connected());r.put("subscriptions",l.desired.size());r.put("messages",l.messages.get());
        r.put("reconnects",l.reconnects.get());r.put("resyncs",l.resyncs.get());r.put("lastFrameAt",l.lastFrame==0?null:l.lastFrame);
        r.put("error",l.error);return r;
    }
    @PreDestroy void stop(){for(Lane l:lanes.values()){l.stopped=true;l.disconnect();l.timer.shutdownNow();}}
    final class Lane {
        final String type;
        final ScheduledExecutorService timer;
        final ConcurrentMap<String,DepthBook> latest=new ConcurrentHashMap<>();
        final AtomicLong generation=new AtomicLong(),messages=new AtomicLong(),reconnects=new AtomicLong(),resyncs=new AtomicLong();
        volatile Map<String,ExchangeDepthSource.Spec> desired=Collections.emptyMap();
        volatile Set<String> subscribed=Collections.emptySet();
        volatile WebSocketSession session;
        volatile boolean stopped;
        volatile String error;
        volatile long connectedAt,lastFrame,lastPing,lastPong,retryAt;
        int failures;
        Lane(String type){this.type=type;timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"depth-ws-"+type);t.setDaemon(true);return t;});}
        boolean connected(){WebSocketSession s=session;return s!=null&&s.isOpen();}
        void tick(){
            if(stopped||!enabled||!source.enabled||desired.isEmpty()){disconnect();return;}
            long now=System.currentTimeMillis();boolean okx=source.okx();
            try{
                if(connected()&&!subscribed.equals(desired.keySet())){disconnect();return;}
                if(!connected()){
                    if(now<retryAt)return;
                    final long connection=generation.incrementAndGet();final Map<String,ExchangeDepthSource.Spec> targets=desired;
                    StandardWebSocketClient client=new StandardWebSocketClient();client.setUserProperties(Collections.singletonMap("org.apache.tomcat.websocket.IO_TIMEOUT_MS","5000"));
                    WebSocketSession opened=client.doHandshake(new TextWebSocketHandler(){
                        @Override public void afterConnectionEstablished(WebSocketSession s){s.setTextMessageSizeLimit(65536);}
                        @Override protected void handlePongMessage(WebSocketSession s,PongMessage m){if(connection==generation.get())lastPong=System.currentTimeMillis();}
                        @Override protected void handleTextMessage(WebSocketSession s,TextMessage m){
                            if(connection!=generation.get()||stopped)return;
                            long at=System.currentTimeMillis();lastFrame=at;
                            if(okx&&"pong".equals(m.getPayload())){lastPong=at;return;}
                            try{
                                JsonNode root=ExchangeQuoteSource.JSON.readTree(m.getPayload());
                                if(root.has("code")||"error".equals(root.path("event").asText())){fail("subscription_error");return;}
                                if(root.has("result")||"subscribe".equals(root.path("event").asText()))return;
                                JsonNode row=okx?root.path("data").path(0):root.path("data");
                                if("serverShutdown".equals(row.path("e").asText())){fail("server_shutdown");return;}
                                if(okx&&!"books5".equals(root.path("arg").path("channel").asText()))return;
                                String external=okx?root.path("arg").path("instId").asText():root.path("stream").asText().split("@",2)[0].toUpperCase(Locale.ROOT);
                                ExchangeDepthSource.Spec spec=targets.get(external);
                                if(spec==null||!desired.containsKey(external))return;
                                if(row.has("instId")&&!external.equals(row.path("instId").asText())||row.has("s")&&!external.equals(row.path("s").asText()))throw DepthBook.invalid("instrument_mismatch");
                                DepthBook book=DepthBook.parse(row,okx,"swap".equals(type),true,at);
                                synchronized(Lane.this){
                                    if(connection!=generation.get()||stopped)return;
                                    DepthBook old=latest.get(external);
                                    if(old!=null){int order=new BigInteger(book.sequence).compareTo(new BigInteger(old.sequence));
                                        boolean reset=okx&&order<0&&book.sourceAsOf!=null&&old.sourceAsOf!=null&&book.sourceAsOf>old.sourceAsOf;
                                        if(order<0&&!reset||order==0&&!book.sameContent(old)||book.sourceAsOf!=null&&old.sourceAsOf!=null&&book.sourceAsOf<old.sourceAsOf)throw DepthBook.invalid("out_of_order_snapshot");
                                    }
                                    latest.put(external,book);messages.incrementAndGet();error=null;
                                }
                            }catch(Exception invalid){resyncs.incrementAndGet();fail(invalid instanceof MarketHttp.Failure?invalid.getMessage():"invalid_snapshot");}
                        }
                        @Override public void afterConnectionClosed(WebSocketSession s,CloseStatus status){if(connection==generation.get())fail("disconnected");}
                        @Override public void handleTransportError(WebSocketSession s,Throwable cause){if(connection==generation.get())fail("transport_failure");}
                    },okx?okxUrl:"swap".equals(type)?futuresUrl:spotUrl).get(6,TimeUnit.SECONDS);
                    synchronized(this){if(connection!=generation.get()||stopped||!targets.keySet().equals(desired.keySet())){opened.close();return;}
                        session=opened;connectedAt=lastFrame=lastPong=System.currentTimeMillis();lastPing=0;subscribed=new HashSet<>(targets.keySet());reconnects.incrementAndGet();}
                    Map<String,Object> request=new LinkedHashMap<>();
                    if(okx){List<Map<String,String>> args=new ArrayList<>();for(String symbol:targets.keySet()){Map<String,String>a=new LinkedHashMap<>();a.put("channel","books5");a.put("instId",symbol);args.add(a);}request.put("op","subscribe");request.put("args",args);}
                    else{
                        session.sendMessage(new TextMessage("{\"method\":\"SET_PROPERTY\",\"params\":[\"combined\",true],\"id\":1}"));
                        List<String> params=new ArrayList<>();for(String symbol:targets.keySet())params.add(symbol.toLowerCase(Locale.ROOT)+"@depth20"+("swap".equals(type)?"@500ms":""));
                        request.put("method","SUBSCRIBE");request.put("params",params);request.put("id",2);
                    }
                    session.sendMessage(new TextMessage(ExchangeQuoteSource.JSON.writeValueAsString(request)));return;
                }
                if(now-connectedAt>=rotationMs){fail("rotation");return;}
                if(lastPing>lastPong&&now-lastPing>10000){fail("heartbeat_timeout");return;}
                if(now-lastPing>=20000){session.sendMessage(okx?new TextMessage("ping"):new PingMessage());lastPing=now;}
                if(now-lastFrame>maxAgeMs||now-connectedAt>maxAgeMs&&latest.size()<desired.size()){fail("snapshot_timeout");return;}
                if(now-connectedAt>30000)failures=0;
            }catch(Exception e){fail("connection_failure");}
        }
        synchronized void fail(String reason){if(stopped)return;error=reason;failures=Math.min(6,failures+1);retryAt=System.currentTimeMillis()+Math.min(30000,1000L<<(failures-1));disconnect();}
        synchronized void disconnect(){generation.incrementAndGet();WebSocketSession old=session;session=null;subscribed=Collections.emptySet();latest.clear();if(old!=null)try{old.close();}catch(Exception ignored){}}
    }
}
