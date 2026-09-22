package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.admin.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CryptoPerpetualTest {
    static String instrument(String code,String base,String type,String underlying,String status) {
        return "{\"symbol\":\""+code+"\",\"baseAsset\":\""+base+"\",\"quoteAsset\":\"USDT\",\"contractType\":\""+type+"\",\"underlyingType\":\""+underlying+"\",\"status\":\""+status+"\"}";
    }
    @Test void catalogSeparatesPerpetualsAndAllowsSpotCoexistence() {
        MarketInstrumentCatalog catalog=new MarketInstrumentCatalog();
        catalog.exchange=new ExchangeQuoteSource();catalog.http=mock(MarketHttp.class);catalog.symbols=mock(TradingSymbolRepository.class);
        TradingSymbol spot=new TradingSymbol();spot.setSymbol("BTCUSDT");spot.setAlltickSymbol("BTCUSDT");spot.setSourceCategory("Crypto");spot.setMarketSource("binance");
        when(catalog.symbols.findAll()).thenReturn(Collections.singletonList(spot));
        String body="{\"symbols\":["+String.join(",",
            instrument("BTCUSDT","BTC","PERPETUAL","COIN","TRADING"),
            instrument("XAUUSDT","XAU","PERPETUAL","COIN","TRADING"),
            instrument("BTCUSDT_260925","BTC","CURRENT_QUARTER","COIN","TRADING"),
            instrument("OLDUSDT","OLD","PERPETUAL","COIN","BREAK"),
            instrument("STOCKUSDT","STOCK","PERPETUAL","EQUITY","TRADING"))+ "]}";
        when(catalog.http.get(any(URI.class),anyInt())).thenReturn(ResponseEntity.ok(body));
        List<Map<String,Object>> rows=(List<Map<String,Object>>)catalog.list("binance","CryptoPerpetual","",0).get("list");
        assertEquals(1,rows.size());assertEquals("BTCUSDT",rows.get(0).get("symbol"));
        assertEquals(false,rows.get(0).get("added"));assertFalse(rows.get(0).containsKey("unavailableReason"));
        TradingSymbol perpetual=catalog.resolve("binance","CryptoPerpetual","BTCUSDT");
        assertEquals("BTCUSDT_PERP",perpetual.getSymbol());assertEquals("BTCUSDT",perpetual.getAlltickSymbol());
        assertEquals("binance:CryptoPerpetual:BTCUSDT",perpetual.getMarketInstrumentKey());
        when(catalog.symbols.findAll()).thenReturn(Arrays.asList(spot,perpetual));
        rows=(List<Map<String,Object>>)catalog.list("binance","CryptoPerpetual","",0).get("list");
        assertEquals(true,rows.get(0).get("added"));assertFalse(rows.get(0).containsKey("unavailableReason"));
        verify(catalog.http).get(argThat(uri->uri.toString().equals("https://fapi.binance.com/fapi/v1/exchangeInfo")),anyInt());
        assertTrue(catalog.sources().toString().contains("CryptoPerpetual"));
        MarketCategoryService categories=new MarketCategoryService();categories.configs=mock(SystemConfigService.class);
        List<Map<String,Object>> bindings=categories.all();
        bindings.stream().filter(row->"Crypto".equals(row.get("key"))).findFirst().get().put("sourceCategory","CryptoPerpetual");
        assertDoesNotThrow(()->categories.save(bindings));
    }
    @Test void tickerAndKlineUseSeparateBinanceEndpoints() {
        ExchangeQuoteSource source=new ExchangeQuoteSource();source.http=mock(MarketHttp.class);
        when(source.http.get(any(URI.class))).thenAnswer(call->{String url=call.getArgument(0).toString();
            if(url.contains("/klines"))return ResponseEntity.ok("[[1781841600000,100,101,99,100,1,1781841660000,100]]");
            return ResponseEntity.ok("{\"symbol\":\"BTCUSDT\",\"lastPrice\":\""+(url.contains("/fapi/")?"110":"100")+"\",\"closeTime\":"+System.currentTimeMillis()+"}");
        });
        assertEquals(100d,source.prices(Collections.singletonList("BTCUSDT"),"Crypto").get("BTCUSDT").get("price"));
        Map<String,Object> perpetual=source.prices(Collections.singletonList("BTCUSDT"),"CryptoPerpetual").get("BTCUSDT");
        assertEquals(110d,perpetual.get("price"));assertEquals("perpetual",perpetual.get("marketType"));
        source.kline("BTCUSDT","1m",10,"Crypto",null);source.kline("BTCUSDT","1m",10,"CryptoPerpetual",null);
        verify(source.http).get(URI.create("https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1m&limit=10"));
        verify(source.http).get(URI.create("https://fapi.binance.com/fapi/v1/klines?symbol=BTCUSDT&interval=1m&limit=10"));
        assertTrue(ExchangeQuoteSource.supports("CryptoPerpetual"));
        assertEquals("BTC-USDT-SWAP",ExchangeQuoteSource.symbol("BTCUSDT","CryptoPerpetual",true));
    }
    @Test void sameProviderSymbolHasIndependentSubscriptionsAndPrices() {
        ForexQuoteMarketService market=new ForexQuoteMarketService();ExchangeQuoteStream stream=mock(ExchangeQuoteStream.class);
        TradingSymbol spot=new TradingSymbol(),perpetual=new TradingSymbol();
        spot.setId(1L);spot.setSymbol("BTCUSDT");spot.setSourceCategory("Crypto");spot.setMarketSource("binance");spot.setQuoteCurrency("USDT");
        perpetual.setId(2L);perpetual.setSymbol("BTCUSDT_PERP");perpetual.setAlltickSymbol("BTCUSDT");perpetual.setSourceCategory("CryptoPerpetual");perpetual.setMarketSource("binance");perpetual.setQuoteCurrency("USDT");
        TradingSymbolRepository symbols=mock(TradingSymbolRepository.class);when(symbols.findAll()).thenReturn(Arrays.asList(perpetual,spot));
        RedisMarketService redis=mock(RedisMarketService.class);
        ReflectionTestUtils.setField(market,"symbols",symbols);ReflectionTestUtils.setField(market,"redis",redis);ReflectionTestUtils.setField(market,"exchangeStream",stream);
        try {
            market.refreshSymbols();
            verify(stream).subscriptions(eq("Crypto"),eq(Collections.singleton("BTCUSDT")),any());
            verify(stream).subscriptions(eq("CryptoPerpetual"),eq(Collections.singleton("BTCUSDT")),any());
            long now=System.currentTimeMillis();Map<String,Object> quote=new HashMap<>();quote.put("price",100d);quote.put("timestamp",now);
            assertTrue(market.acceptQuote("BTCUSDT","Crypto",quote,"http",now));
            quote=new HashMap<>(quote);quote.put("price",110d);
            assertTrue(market.acceptQuote("BTCUSDT","CryptoPerpetual",quote,"http",now));
            assertEquals(100d,market.snapshotPrice("BTCUSDT").get("price"));
            assertEquals(110d,market.snapshotPrice("BTCUSDT_PERP").get("price"));
            verify(redis).getPrice("Crypto:BTCUSDT");verify(redis).getPrice("CryptoPerpetual:BTCUSDT");
        } finally {market.stop();}
    }
}
