package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Deterministic protocol fixtures are only tests; runtime always uses the selected official source. */
class MarketDepthTest {
    static final String BOOK="{\"lastUpdateId\":90071992547409931234,\"bids\":[[\"98\",\"0.2\"],[\"100.00000000000000000001\",\"0.1\"],[\"99\",\"0\"]],\"asks\":[[\"102\",\"0.3\"],[\"101.00000000000000000001\",\"0.1\"]]}";
    MarketDepthService service;
    static DepthBook book(String json,boolean okx,boolean future,boolean ws)throws Exception{return DepthBook.parse(ExchangeQuoteSource.JSON.readTree(json),okx,future,ws,System.currentTimeMillis());}
    static void until(BooleanSupplier condition)throws Exception{long end=System.currentTimeMillis()+5000;while(!condition.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(20);assertTrue(condition.getAsBoolean(),"depth synchronization timed out");}
    @AfterEach void stop(){if(service!=null)service.stop();}
    @SuppressWarnings("unchecked") @Test void exactDecimalsSortingCumulativeAndDisplayedRange()throws Exception{
        DepthBook b=book(BOOK,false,false,false);Map<String,Object> v=b.view(20);
        List<Map<String,String>> bids=(List<Map<String,String>>)v.get("bids");
        assertEquals("100.00000000000000000001",bids.get(0).get("price"));
        assertEquals("0.3",bids.get(1).get("cumulativeQuantity"));assertEquals(2,bids.size());
        assertEquals("1",v.get("spread"));assertEquals("90071992547409931234",v.get("sequence"));
        assertNull(v.get("sourceAsOf"));assertEquals("NOT_PROVIDED",v.get("sourceTimeBasis"));
        assertEquals("0.42857143",v.get("bidRatio"));assertEquals("0.57142857",v.get("askRatio"));
        Map<String,Object> one=b.view(1);assertEquals("0.5",one.get("bidRatio"));assertEquals("0.1",one.get("bidQuantity"));
        DepthBook replacement=book("{\"lastUpdateId\":90071992547409931999,\"bids\":[[\"98\",\"1\"]],\"asks\":[]}",false,false,true);
        assertEquals(1,replacement.bids.size());assertNull(replacement.view(20).get("spread"));assertNull(replacement.view(20).get("bidRatio"));assertFalse(b.sameContent(replacement));
        assertNull(book("{\"lastUpdateId\":1,\"bids\":[],\"asks\":[]}",false,false,false).view(20).get("bidRatio"));
    }
    @Test void rejectsInvalidLevelsSequenceTimesAndOverflow()throws Exception{
        for(String json:Arrays.asList(BOOK.replace("\"0.2\"","\"-0.2\""),BOOK.replace("\"98\"","\"-98\""),BOOK.replace("\"98\"","\"100.00000000000000000001\""),BOOK.replace("\"0.2\"","\"1e99999\""),BOOK.replace("\"102\"","\"95\""),BOOK.replace("90071992547409931234","-1"),"{\"lastUpdateId\":1,\"bids\":null,\"asks\":[]}"))
            assertThrows(MarketHttp.Failure.class,()->book(json,false,false,false));
        assertThrows(MarketHttp.Failure.class,()->book("{\"u\":1,\"b\":[],\"a\":[]}",false,true,true));
        String future="{\"u\":2,\"T\":"+System.currentTimeMillis()+",\"b\":[[\"100\",\"1\"]],\"a\":[[\"101\",\"2\"]]}";
        assertEquals("WS_SNAPSHOT",book(future,false,true,true).transport);
        assertThrows(MarketHttp.Failure.class,()->book(future.replaceFirst("\"T\":\\d+","\"T\":99999999999999"),false,true,true));
    }
    ExchangeDepthSource source(String provider){ExchangeDepthSource s=new ExchangeDepthSource();s.exchange=new ExchangeQuoteSource();s.exchange.provider=provider;s.exchange.spotUrl="https://data-api.binance.vision";s.exchange.futuresUrl="https://fapi.binance.com";s.exchange.okxUrl="https://www.okx.com";s.http=mock(MarketHttp.class);return s;}
    @Test void officialCatalogUnitsSingleProviderAndBusinessErrors()throws Exception{
        ExchangeDepthSource s=source("okx");AtomicInteger calls=new AtomicInteger();
        when(s.http.get(any(URI.class),anyInt())).thenAnswer(i->{calls.incrementAndGet();URI u=i.getArgument(0);assertEquals("www.okx.com",u.getHost());
            if(u.getPath().contains("instruments"))return ResponseEntity.ok("{\"code\":\"0\",\"data\":[{\"instId\":\"BTC-USDT-SWAP\",\"instType\":\"SWAP\",\"state\":\"live\",\"ctVal\":\"0.01\",\"ctMult\":\"1\",\"ctValCcy\":\"BTC\",\"settleCcy\":\"USDT\"}]}");
            return ResponseEntity.ok("{\"code\":\"50000\",\"msg\":\"do not disclose upstream content\"}");});
        s.validate();ExchangeDepthSource.Spec spec=s.spec("BTC-USDT-SWAP","swap");assertEquals("CONTRACT",spec.quantityUnit);assertEquals("0.01",spec.contractValue);assertEquals("BTC",spec.contractValueCurrency);
        s.spec("BTC-USDT-SWAP","swap");assertEquals(1,calls.get(),"catalog must be cached");
        assertThrows(MarketHttp.Failure.class,()->s.spec("UNKNOWN","swap"));assertThrows(MarketHttp.Failure.class,()->s.snapshot(spec));
        ExchangeDepthSource b=source("binance");when(b.http.get(any(URI.class),anyInt())).thenReturn(ResponseEntity.ok("{\"symbols\":[{\"symbol\":\"BTCUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"BTC\",\"quoteAsset\":\"USDT\",\"contractType\":\"PERPETUAL\"}]}"));
        assertEquals("BASE_ASSET",b.spec("BTCUSDT","swap").quantityUnit);verify(b.http).get(eq(URI.create("https://fapi.binance.com/fapi/v1/exchangeInfo")),eq(8*1024*1024));
        assertThrows(IllegalArgumentException.class,()->b.official("http://www.okx.com","https",Collections.singleton("www.okx.com")));
        assertThrows(IllegalArgumentException.class,()->b.official("https://data-api.binance.vision.evil.invalid","https",Collections.singleton("data-api.binance.vision")));
        assertThrows(IllegalArgumentException.class,()->b.official("https://user:secret@data-api.binance.vision","https",Collections.singleton("data-api.binance.vision")));
        when(b.http.get(any(URI.class),anyInt())).thenThrow(new MarketHttp.Failure("http_400",0));
        assertEquals("unsupported_instrument",assertThrows(MarketHttp.Failure.class,()->b.spec("NOTEXISTUSDT","spot")).getMessage());
        when(s.http.get(any(URI.class),anyInt())).thenReturn(ResponseEntity.ok("{\"code\":\"0\",\"data\":[]}"));
        assertEquals("unsupported_instrument",assertThrows(MarketHttp.Failure.class,()->s.spec("NOTEXIST-USDT","spot")).getMessage());
    }
    static TradingSymbol symbol(long id,String name,String category){TradingSymbol s=new TradingSymbol();s.setId(id);s.setSymbol(name);s.setIsEnabled(true);s.setSourceCategory(category);s.setAlltickSymbol(name);s.setBaseCurrency("BTC");s.setQuoteCurrency("USDT");return s;}
    Map<Long,String> enabled=new HashMap<>();
    void setup()throws Exception{
        service=new MarketDepthService();service.symbols=mock(TradingSymbolRepository.class);service.configs=mock(SystemConfigService.class);service.source=mock(ExchangeDepthSource.class);service.source.exchange=new ExchangeQuoteSource();service.source.exchange.provider="binance";service.stream=mock(ExchangeDepthStream.class);
        when(service.configs.getConfigValue(MarketDepthService.ENABLED_KEY)).thenAnswer(i->enabled.get(TenantContext.requireTenantId()));
        when(service.symbols.findByTenantIdAndSymbol(anyLong(),anyString())).thenAnswer(i->{String n=i.getArgument(1);Long tenant=i.getArgument(0);return "MISSING".equals(n)||"SECRET".equals(n)&&tenant!=1L?Optional.empty():Optional.of(symbol(tenant*100,n,"EURUSD".equals(n)?"Forex":"Crypto"));});
        when(service.source.spec(anyString(),anyString())).thenAnswer(i->new ExchangeDepthSource.Spec(i.getArgument(0),i.getArgument(1),"BASE_ASSET","BTC","USDT",null,null,null));
        when(service.source.snapshot(any())).thenAnswer(i->book(BOOK,false,false,false));
    }
    Map<String,Object> read(long tenant,String name,String owner){try(TenantContext.Scope scope=TenantContext.open(tenant)){return service.read(name,20,null,owner);}}
    @Test void cacheTenantVisibilityDisableAndIdleLifecycle()throws Exception{
        setup();assertEquals("SYNCING",read(1,"BTCUSDT","rest:1:BTCUSDT").get("status"));service.tick();
        until(()->"LIVE".equals(read(1,"BTCUSDT","rest:1:BTCUSDT").get("status")));
        Map<String,Object> demo=read(1,"BTCUSDT","ws:demo");assertTrue((Boolean)demo.get("cacheHit"));
        read(2,"BTCUSDT","ws:tenant2");service.tick();verify(service.source,times(1)).snapshot(any());
        assertEquals("UNSUPPORTED",read(2,"SECRET","ws:tenant2-secret").get("status"));
        enabled.put(1L,"false");service.disableTenant(1L);assertEquals("DISABLED",read(1,"BTCUSDT","ws:demo").get("status"));assertEquals("LIVE",read(2,"BTCUSDT","ws:tenant2").get("status"));
        service.release("ws:tenant2");assertEquals("SYNCING",read(2,"BTCUSDT","ws:tenant2").get("status"));
        service.release("ws:tenant2");service.idleMs=-1;read(2,"ETHUSDT","rest:2:ETHUSDT");service.tick();
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(service,"cache")).isEmpty());
    }
    @SuppressWarnings("unchecked") @Test void staleResyncUnsupportedCapacityAndRetryAfter()throws Exception{
        setup();read(1,"BTCUSDT","rest:1:BTCUSDT");service.tick();until(()->"LIVE".equals(read(1,"BTCUSDT","rest:1:BTCUSDT").get("status")));
        MarketDepthService.Entry e=((Map<String,MarketDepthService.Entry>)ReflectionTestUtils.getField(service,"cache")).get("spot:BTCUSDT");
        e.book=new DepthBook(e.book.sequence,null,System.currentTimeMillis()-20000,"WS_SNAPSHOT",e.book.bids,e.book.asks);assertEquals("STALE",read(1,"BTCUSDT","rest:1:BTCUSDT").get("status"));
        e.nextPoll=0;when(service.source.snapshot(any())).thenThrow(new MarketHttp.Failure("http_429",60000));service.tick();until(()->!e.pending);
        assertEquals("STALE",read(1,"BTCUSDT","rest:1:BTCUSDT").get("status"));assertTrue(e.nextPoll>System.currentTimeMillis()+50000);assertEquals("http_429",e.error);
        long count=e.restRequests;service.tick();assertEquals(count,e.restRequests);
        for(int i=0;i<15;i++)read(1,"COIN"+i+"USDT","rest:1:"+i);
        assertEquals("subscription_limit",read(1,"ETHUSDT","rest:1:extra").get("reason"));
        assertEquals("UNSUPPORTED",read(1,"EURUSD","ws:forex").get("status"));assertEquals("unknown_or_disabled_instrument",read(1,"MISSING","ws:unknown").get("reason"));
        try(TenantContext.Scope scope=TenantContext.open(1L)){assertEquals("market_type_mismatch",service.read("BTCUSDT",20,"swap","ws:type").get("reason"));assertThrows(IllegalArgumentException.class,()->service.read("BTCUSDT",21,null,"ws:invalid"));}
    }
    @SuppressWarnings("unchecked") @Test void delistingRevalidationAndDbFailureStillExpireLeases()throws Exception{
        setup();read(1,"BTCUSDT","rest:1:BTCUSDT");service.tick();until(()->"LIVE".equals(read(1,"BTCUSDT","rest:1:BTCUSDT").get("status")));
        MarketDepthService.Entry e=((Map<String,MarketDepthService.Entry>)ReflectionTestUtils.getField(service,"cache")).get("spot:BTCUSDT");
        e.metadataCheckedAt=0;e.nextPoll=0;when(service.source.spec(anyString(),anyString())).thenThrow(DepthBook.invalid("unsupported_instrument"));service.tick();until(()->!e.pending);
        assertEquals("UNSUPPORTED",read(1,"BTCUSDT","rest:1:BTCUSDT").get("status"));assertNull(e.book);
        service.release("rest:1:BTCUSDT");service.idleMs=-1;read(1,"ETHUSDT","rest:1:ETHUSDT");doThrow(new IllegalStateException("isolated DB failure")).when(service.configs).getConfigValue(MarketDepthService.ENABLED_KEY);service.tick();
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(service,"cache")).isEmpty());
    }
}
