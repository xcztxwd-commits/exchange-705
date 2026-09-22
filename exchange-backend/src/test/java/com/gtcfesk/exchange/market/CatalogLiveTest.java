package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CATALOG_LIVE_TEST",matches="true")
class CatalogLiveTest {
    @Test void publicCatalogsResolveRealProviderMetadata() {
        MarketInstrumentCatalog catalog=new MarketInstrumentCatalog();catalog.http=new MarketHttp();catalog.exchange=new ExchangeQuoteSource();
        catalog.symbols=mock(TradingSymbolRepository.class);when(catalog.symbols.findAll()).thenReturn(Collections.emptyList());
        try {
            Map<String,Object> list=catalog.list("binance","Crypto","",0);
            assertTrue(((Number)list.get("total")).intValue()>500);assertEquals(50,((List<?>)list.get("list")).size());
            assertEquals("BTC",catalog.resolve("binance","Crypto","ETHBTC").getQuoteCurrency());
            assertEquals("USD",catalog.resolve("yahoo","US","AAPL").getQuoteCurrency());
            assertEquals("EURUSD=X",catalog.resolve("yahoo","Forex","EURUSD=X").getAlltickSymbol());
            assertEquals("^NDX",catalog.resolve("yahoo","CFD","^NDX").getAlltickSymbol());
            System.out.println("CATALOG_LIVE: Binance active spot count="+list.get("total")+"; ETHBTC, AAPL, EURUSD=X, ^NDX resolved");
        } finally {catalog.http.stop();}
    }
}
