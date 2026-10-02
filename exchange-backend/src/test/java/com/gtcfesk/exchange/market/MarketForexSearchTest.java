package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MarketForexSearchTest extends TenantMarketTestContext {
    @Test void searchingVisiblePairFindsProviderCodeWithoutChangingIt() {
        MarketController controller = new MarketController();
        TradingSymbolRepository repository = mock(TradingSymbolRepository.class);
        MarketCategoryService categories = mock(MarketCategoryService.class);
        TradingSymbol symbol = new TradingSymbol();
        symbol.setId(1L); symbol.setSymbol("JPY=X"); symbol.setCategory("Forex");
        when(repository.searchSymbolsByKeyword("usd/jpy")).thenReturn(Collections.emptyList());
        when(repository.searchSymbolsByKeyword("JPY=X")).thenReturn(Collections.singletonList(symbol));
        when(categories.all()).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "symbolRepository", repository);
        ReflectionTestUtils.setField(controller, "categoryService", categories);

        Map<?, ?> body = (Map<?, ?>) controller.searchSymbols("usd/jpy").getBody();
        List<?> rows = (List<?>) body.get("list");
        assertEquals(1, rows.size());
        assertEquals("JPY=X", ((TradingSymbol) rows.get(0)).getSymbol());
        assertEquals("USD/JPY", ((TradingSymbol) rows.get(0)).getDisplayName());
    }
}
