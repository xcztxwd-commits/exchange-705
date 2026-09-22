package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketInstrumentCatalogTest {
    final MarketInstrumentCatalog catalog=new MarketInstrumentCatalog();
    MarketInstrumentCatalogTest(){catalog.http=mock(MarketHttp.class);catalog.symbols=mock(TradingSymbolRepository.class);catalog.exchange=new ExchangeQuoteSource();when(catalog.symbols.findAll()).thenReturn(Collections.emptyList());}
    static String spot(){return "{\"symbols\":[{\"symbol\":\"ETHBTC\",\"status\":\"TRADING\",\"isSpotTradingAllowed\":true,\"baseAsset\":\"ETH\",\"quoteAsset\":\"BTC\",\"filters\":[{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.00001000\"},{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.00100000\",\"minQty\":\"0.001\"}]},{\"symbol\":\"OLDUSDT\",\"status\":\"BREAK\",\"isSpotTradingAllowed\":true}]}";}
    @Test void exchangeMetadataAddedStateAndSourceCategoryAreIndependent(){
        when(catalog.http.get(any(URI.class),anyInt())).thenReturn(ResponseEntity.ok(spot()));
        TradingSymbol symbol=catalog.resolve("binance","Crypto","ETHBTC");
        assertEquals("BTC",symbol.getQuoteCurrency());assertEquals(5,symbol.getPricePrecision());assertEquals(3,symbol.getVolumePrecision());
        symbol.setCategory("US");symbol.setIsEnabled(false);
        assertEquals("binance:Crypto:ETHBTC",MarketInstrumentCatalog.identity(symbol));
        when(catalog.symbols.findAll()).thenReturn(Collections.singletonList(symbol));
        Map<String,Object> result=catalog.list("binance","Crypto","",0);
        assertEquals(1,result.get("total"));Map<?,?> row=(Map<?,?>)((List<?>)result.get("list")).get(0);assertEquals(true,row.get("added"));
        assertTrue(((List<?>)catalog.list("binance","Crypto","no-match",0).get("list")).isEmpty());
        verify(catalog.http,times(1)).get(any(URI.class),anyInt());
        assertThrows(BusinessException.class,()->catalog.resolve("binance","Crypto","OLDUSDT"));
        assertThrows(BusinessException.class,()->catalog.resolve("yahoo","Crypto","ETHBTC"));
        assertEquals("ETHBTC",ExchangeQuoteSource.symbol("ETHBTC","Crypto",false));
    }
    @Test void yahooUsesExactProviderIdentifiersAndVerifiedCurrency(){
        when(catalog.http.get(any(URI.class),anyInt())).thenAnswer(call->{String uri=call.getArgument(0).toString();
            assertTrue(uri.startsWith("https://query1.finance.yahoo.com/"));
            return ResponseEntity.ok(uri.contains("/lookup?")?"{\"finance\":{\"result\":[{\"documents\":[{\"symbol\":\"^NDX\",\"shortName\":\"NASDAQ 100\",\"quoteType\":\"INDEX\"}],\"lookupTotals\":{\"index\":1}}]}}":"{\"chart\":{\"result\":[{\"meta\":{\"symbol\":\"^NDX\",\"currency\":\"USD\",\"priceHint\":2}}]}}");});
        TradingSymbol symbol=catalog.resolve("yahoo","CFD","^NDX");
        assertEquals("^NDX",symbol.getAlltickSymbol());assertEquals("yahoo:CFD:^NDX",symbol.getMarketInstrumentKey());
        assertEquals("^NDX",MarketQuoteSource.mapSymbolToYahoo("^NDX","CFD"));
        assertThrows(BusinessException.class,()->catalog.resolve("yahoo","CFD","^FAKE"));
        assertNull(catalog.list("yahoo","CFD","^",0).get("total"));
        assertTrue(((List<?>)catalog.list("yahoo","US","",0).get("list")).isEmpty());
    }
    @Test void providerFailureDoesNotProduceFallbackCatalog(){
        when(catalog.http.get(any(URI.class),anyInt())).thenThrow(new MarketHttp.Failure("http_429",2000));
        assertThrows(BusinessException.class,()->catalog.list("binance","Crypto","",0));
        assertThrows(BusinessException.class,()->catalog.list("binance","Crypto","",200));
    }
    @Test void categoryBindingAllowsManualOverrideAndPreservesHiddenCategories(){
        MarketCategoryService categories=new MarketCategoryService();categories.configs=mock(SystemConfigService.class);
        assertEquals("Crypto",categories.projectCategory(null,"binance","Crypto"));
        assertEquals("US",categories.projectCategory("US","binance","Crypto"));
        List<Map<String,Object>> rows=categories.all();rows.get(0).put("enabled",false);
        List<Map<String,Object>> saved=categories.save(rows);assertEquals(false,saved.get(0).get("enabled"));
        rows.get(0).put("marketSource","binance");assertThrows(BusinessException.class,()->categories.save(rows));
        assertThrows(BusinessException.class,()->categories.projectCategory("missing","binance","Crypto"));
    }
}
