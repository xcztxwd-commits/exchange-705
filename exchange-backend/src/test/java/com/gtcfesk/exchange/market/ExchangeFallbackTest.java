package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeFallbackTest {
    @Test void httpTakesOverOnDisconnectAndHealthyStreamSuppressesPolls() {
        ForexQuoteMarketService market=new ForexQuoteMarketService();
        ExchangeQuoteStream stream=mock(ExchangeQuoteStream.class);MarketQuoteSource source=mock(MarketQuoteSource.class);
        TradingSymbolRepository repository=mock(TradingSymbolRepository.class);TradingSymbol symbol=new TradingSymbol();
        symbol.setId(1L);symbol.setSymbol("BTCUSD");symbol.setAlltickSymbol("BTCUSDT");symbol.setCategory("CFD"); symbol.setSourceCategory("Crypto"); symbol.setMarketSource(com.gtcfesk.exchange.market.MarketInstrumentCatalog.inferredSource("Crypto"));
        when(repository.findAll()).thenReturn(Collections.singletonList(symbol));
        ReflectionTestUtils.setField(market,"symbols",repository);ReflectionTestUtils.setField(market,"redis",mock(RedisMarketService.class));
        ReflectionTestUtils.setField(market,"source",source);ReflectionTestUtils.setField(market,"http",mock(MarketHttp.class));
        ReflectionTestUtils.setField(market,"exchangeStream",stream);market.refreshSymbols();
        try{
            Map<String,Object> quote=new HashMap<>();long now=System.currentTimeMillis();quote.put("timestamp",now);quote.put("price",100d);
            when(stream.connected("Crypto")).thenReturn(true);when(stream.healthy("Crypto","BTCUSDT")).thenReturn(true);
            assertTrue(market.acceptQuote("BTCUSDT","Crypto",quote,"ws",now));
            assertEquals(100d,((Number)market.snapshotPrice("BTCUSD").get("price")).doubleValue());
            Map<?,?> groups=(Map<?,?>)ReflectionTestUtils.getField(market,"groups");Object group=groups.get("Crypto");
            ReflectionTestUtils.invokeMethod(market,"tick",group);verify(source,never()).getBatchPrices(anyList(),anyString());
            assertFalse(market.acceptQuote("BTCUSDT","Crypto",quote,"http",now));
            when(stream.connected("Crypto")).thenReturn(false);when(stream.healthy("Crypto","BTCUSDT")).thenReturn(false);
            assertFalse((Boolean)market.getPrice("BTCUSDT","Crypto").get("tradeAvailable"));
            when(source.getBatchPrices(anyList(),eq("Crypto"))).thenReturn(Collections.singletonMap("BTCUSDT",quote));
            ReflectionTestUtils.invokeMethod(market,"tick",group);
            assertEquals("http",market.getPrice("BTCUSDT","Crypto").get("transport"));assertTrue((Boolean)market.getPrice("BTCUSDT","Crypto").get("tradeAvailable"));
            quote.put("timestamp",now-1000);assertFalse(market.acceptQuote("BTCUSDT","Crypto",quote,"ws",now));
        }finally{market.stop();}
    }
    @Test void http429And418SuppressFurtherRequestsIncludingKlines()throws Exception{
        for(int status:Arrays.asList(429,418)){
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger requests=new AtomicInteger();
            server.createContext("/",e->{requests.incrementAndGet();e.getResponseHeaders().set("Retry-After","8");e.sendResponseHeaders(status,-1);e.close();});server.start();
            MarketHttp http=new MarketHttp();URI ticker=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/fapi/v1/ticker/24hr");
            try{
                MarketHttp.Failure failure=assertThrows(MarketHttp.Failure.class,()->http.get(ticker));assertTrue(failure.retryAfterMs>=8000);
                assertThrows(MarketHttp.Failure.class,()->http.get(ticker.resolve("/fapi/v1/klines")));assertEquals(1,requests.get());
            }finally{http.stop();server.stop(0);}
        }
    }
}
