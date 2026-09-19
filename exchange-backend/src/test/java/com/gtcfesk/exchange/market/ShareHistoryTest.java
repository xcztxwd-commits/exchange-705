package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShareHistoryTest {
    @Test void rejectsUnboundedOrFutureHistoryRequests() {
        MarketKlineController controller = new MarketKlineController();
        assertEquals(400, controller.history("USDJPY", "1m", 1, 160).getStatusCodeValue());
        assertEquals(400, controller.history("USDJPY", "1s", System.currentTimeMillis(), 160).getStatusCodeValue());
        assertEquals(400, controller.history("USDJPY", "1m", System.currentTimeMillis(), 10000).getStatusCodeValue());
    }

    @Test void historicalRequestsUseProviderTimeWhileLiveRequestsStayUnchanged() {
        MarketQuoteSource source = new MarketQuoteSource();
        MarketHttp http = mock(MarketHttp.class);
        ReflectionTestUtils.setField(source, "http", http);
        ReflectionTestUtils.setField(source, "baseUrl", "https://example.invalid/api/v3");
        ReflectionTestUtils.setField(source, "yahooUrl", "https://example.invalid/v8/finance");
        when(http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"code\":\"00000\",\"data\":[[\"1781841600000\",\"100\",\"101\",\"99\",\"100\",\"1\",\"100\"]]}"));
        source.getKline("BTCUSDT", "1m", 160, "Crypto", 1781845200000L);
        verify(http).get(argThat(uri -> uri.toString().contains("/history-candles?") && uri.toString().contains("endTime=1781845200000")));
        source.getKline("BTCUSDT", "1m", 160, "Crypto");
        verify(http).get(argThat(uri -> uri.toString().contains("/market/candles?") && !uri.toString().contains("endTime")));
    }
}
