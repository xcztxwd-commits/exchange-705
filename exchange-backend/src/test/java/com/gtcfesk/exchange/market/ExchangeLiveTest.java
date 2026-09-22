package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in public data smoke; never creates orders or enables the secondary in the application. */
@EnabledIfEnvironmentVariable(named="EXCHANGE_LIVE_TEST",matches="true")
class ExchangeLiveTest {
    @Test void actualBinanceAndOkxPublicRestAndWebsocket()throws Exception{
        for(String provider:Arrays.asList("binance","okx")){
            ExchangeQuoteSource source=new ExchangeQuoteSource();source.provider=provider;source.http=new MarketHttp();
            ExchangeQuoteStream stream=new ExchangeQuoteStream();stream.source=source;
            BlockingQueue<Map<String,Object>> received=new LinkedBlockingQueue<>();
            try{
                assertTrue(QuoteState.valid(source.prices(Arrays.asList("BTCUSDT"),"Crypto").get("BTCUSDT")));
                ForexQuoteMarketService.validateKline(source.kline("BTCUSDT","1m",20,"Crypto",null));
                stream.subscriptions("Crypto",Collections.singleton("BTCUSDT"),(s,q)->received.offer(q));stream.start();
                Map<String,Object> quote=received.poll(20,TimeUnit.SECONDS);assertNotNull(quote,provider+" WebSocket missing");
                assertTrue(System.currentTimeMillis()-QuoteState.time(quote.get("timestamp"))<15000);
                if("okx".equals(provider)){
                    assertEquals(2,source.prices(Arrays.asList("XAUUSD","XAGUSD"),"Metal").size());
                    ForexQuoteMarketService.validateKline(source.kline("XAUUSD","1m",20,"Metal",null));
                    BlockingQueue<Map<String,Object>> metals=new LinkedBlockingQueue<>();
                    stream.subscriptions("Metal",new HashSet<>(Arrays.asList("XAUUSD","XAGUSD")),(s,q)->metals.offer(q));
                    Set<String> seen=new HashSet<>();long until=System.currentTimeMillis()+20000;
                    while(seen.size()<2&&System.currentTimeMillis()<until){Map<String,Object> m=metals.poll(2,TimeUnit.SECONDS);if(m!=null)seen.add((String)m.get("symbol"));}
                    assertEquals(2,seen.size());
                }
                // Stay past a heartbeat period to cover provider ping/pong compatibility.
                Thread.sleep(22000);assertTrue(stream.connected("Crypto"));
                System.out.println("LIVE "+provider+" HTTP ticker/K-line and WebSocket heartbeat passed: "+stream.status("Crypto"));
            }finally{stream.stop();source.http.stop();}
        }
    }
}
