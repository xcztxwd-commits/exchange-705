package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExchangeQuoteTest {
    @Test void exactInstrumentAndIntervalMapping() {
        assertEquals("XAUUSDT",ExchangeQuoteSource.symbol("XAUUSD","Metal",false));
        assertEquals("XAG-USDT-SWAP",ExchangeQuoteSource.symbol("XAGUSD","Metal",true));
        assertEquals("BTC-USDT",ExchangeQuoteSource.symbol("BTCUSDT","Crypto",true));
        assertThrows(MarketHttp.Failure.class,()->ExchangeQuoteSource.symbol("XPTUSD","Metal",false));
        assertThrows(MarketHttp.Failure.class,()->ExchangeQuoteSource.symbol("B/TCUSD","Crypto",false));
        assertEquals("1M",ExchangeQuoteSource.interval("1mo",false));
        assertEquals("1Dutc",ExchangeQuoteSource.interval("1d",true));
        assertEquals("4H",ExchangeQuoteSource.interval("4h",true));
        assertEquals("6Hutc",ExchangeQuoteSource.interval("6h",true));
    }
    @Test void timestampCannotBeReplacedByArrivalAndInvalidPriceRejected() throws Exception {
        long old=System.currentTimeMillis()-60000;
        Map<String,Object> quote=ExchangeQuoteSource.ticker(ExchangeQuoteSource.JSON.readTree("{\"s\":\"BTCUSDT\",\"c\":\"110\",\"o\":\"100\",\"C\":"+old+"}"),false,true);
        assertEquals(old,quote.get("timestamp"));assertEquals(10d,(Double)quote.get("changePct24h"),0.001);
        quote.put("fetchedAt",System.currentTimeMillis());assertFalse((Boolean)QuoteState.view(quote,15000).get("tradeAvailable"));
        for(String payload:Arrays.asList("{\"s\":\"BTCUSDT\",\"c\":\"NaN\",\"C\":"+old+"}","{\"s\":\"BTCUSDT\",\"c\":\"110\"}"))
            assertThrows(MarketHttp.Failure.class,()->ExchangeQuoteSource.ticker(ExchangeQuoteSource.JSON.readTree(payload),false,true));
    }
    @Test void binanceHttpUsesCorrectMarketsAndOkxStaysUnused() {
        ExchangeQuoteSource source=new ExchangeQuoteSource();source.http=mock(MarketHttp.class);
        long now=System.currentTimeMillis();
        when(source.http.get(any(URI.class))).thenAnswer(call->{String url=call.getArgument(0).toString();
            return ResponseEntity.ok("{\"symbol\":\""+(url.contains("/fapi/")?"XAUUSDT":"BTCUSDT")+"\",\"lastPrice\":\"100\",\"closeTime\":"+now+"}");});
        assertEquals("Binance",source.prices(Arrays.asList("BTCUSDT"),"Crypto").get("BTCUSDT").get("source"));
        assertEquals("perpetual",source.prices(Arrays.asList("XAUUSD"),"Metal").get("XAUUSD").get("marketType"));
        verify(source.http,never()).get(argThat(u->u.getHost().contains("okx")));
    }
    @Test void okxExplicitSelectionNormalizesTickerAndHistoricalCandles() {
        ExchangeQuoteSource source=new ExchangeQuoteSource();source.provider="okx";source.http=mock(MarketHttp.class);
        long now=System.currentTimeMillis();
        when(source.http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"code\":\"0\",\"data\":[{\"instId\":\"XAU-USDT-SWAP\",\"last\":\"100\",\"open24h\":\"80\",\"ts\":\""+now+"\"}]}"));
        assertEquals(25d,source.prices(Arrays.asList("XAUUSD"),"Metal").get("XAUUSD").get("changePct24h"));
        when(source.http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"code\":\"0\",\"data\":[[\"1781841600000\",\"100\",\"101\",\"99\",\"100\",\"1\",\"1\",\"100\",\"1\"]]}"));
        ForexQuoteMarketService.validateKline(source.kline("XAUUSD","1d",200,"Metal",1781845200000L));
        verify(source.http).get(argThat(u->u.toString().contains("history-candles")&&u.toString().contains("bar=1Dutc")&&u.toString().contains("after=1781845200000")));
    }
    @Test void malformedAndMismatchedProvidersCannotPublish() {
        ExchangeQuoteSource source=new ExchangeQuoteSource();source.http=mock(MarketHttp.class);
        when(source.http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"symbol\":\"ETHUSDT\",\"lastPrice\":\"100\",\"closeTime\":"+System.currentTimeMillis()+"}"));
        assertThrows(MarketHttp.Failure.class,()->source.prices(Arrays.asList("BTCUSDT"),"Crypto"));
        source.provider="okx";when(source.http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"code\":\"51000\",\"data\":[]}"));
        assertThrows(MarketHttp.Failure.class,()->source.prices(Arrays.asList("BTCUSDT"),"Crypto"));
    }
}
