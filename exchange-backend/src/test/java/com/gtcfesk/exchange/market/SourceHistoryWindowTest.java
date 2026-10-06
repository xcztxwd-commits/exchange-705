package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.*;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual provider decoders with intercepted HTTP; empty history is distinct from transport/protocol failure. */
class SourceHistoryWindowTest {
    @Test void emptyBinanceWindowIsAValidNoDataReceiptNotLiveKlineSuccess() {
        ExchangeQuoteSource exchange = new ExchangeQuoteSource(); exchange.http = mock(MarketHttp.class);
        when(exchange.http.get(any(URI.class))).thenReturn(ResponseEntity.ok("[]"));
        MarketQuoteSource source = new MarketQuoteSource(); ReflectionTestUtils.setField(source, "exchange", exchange);
        Map<String,Object> response = source.getHistoryWindow("BTCUSDT", "5m", 2, "Crypto", 1700000400000L, 1700000999999L);
        assertTrue(ControlHistoryStore.rows(response).isEmpty()); assertEquals(200, response.get("ret"));
        verify(exchange.http).get(argThat(uri -> uri.toString().contains("interval=5m") && uri.toString().contains("endTime=1700000999999")));
        assertThrows(MarketHttp.Failure.class, () -> ForexQuoteMarketService.validateKline(response), "live snapshots still reject empty data");
    }
    @Test void okxIncompleteHistoryConfirmationRemainsPartialAndNoExchangeFailoverOccurs() {
        ExchangeQuoteSource exchange = new ExchangeQuoteSource(); exchange.provider = "okx"; exchange.http = mock(MarketHttp.class);
        when(exchange.http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"code\":\"0\",\"data\":[[\"1700000400000\",\"100\",\"101\",\"99\",\"100\",\"1\",\"1\",\"100\",\"0\"]]}"));
        Map<String,Object> response = exchange.kline("BTCUSDT", "1m", 2, "Crypto", 1700000519999L);
        assertEquals(true, ControlHistoryStore.rows(response).get(0).get("partial"));
        verify(exchange.http).get(argThat(uri -> uri.toString().contains("history-candles") && uri.toString().contains("BTC-USDT")));
        verify(exchange.http, never()).get(argThat(uri -> uri.getHost().contains("binance")));
    }
    @Test void exactYahooHistoryRangeNeverClampsToCurrentRetentionAndErrorIsNotNoData() {
        MarketQuoteSource source = new MarketQuoteSource(); MarketHttp http = mock(MarketHttp.class);
        ReflectionTestUtils.setField(source, "http", http); ReflectionTestUtils.setField(source, "yahooUrl", "https://query1.finance.yahoo.com/v8/finance");
        when(http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"chart\":{\"error\":null,\"result\":[{\"timestamp\":null}]}}"));
        assertTrue(ControlHistoryStore.rows(source.getHistoryWindow("USDJPY", "1m", 2, "Forex", 1700000400000L, 1700000519999L)).isEmpty());
        verify(http).get(argThat(uri -> uri.toString().contains("USDJPY%3DX") && uri.toString().contains("period1=1700000400")
            && uri.toString().contains("period2=1700000520") && uri.toString().contains("includePrePost=false")));
        when(http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"chart\":{\"result\":null,\"error\":{\"code\":\"Unprocessable Entity\",\"description\":\"range unavailable\"}}}"));
        assertEquals("history_source_unavailable", assertThrows(MarketHttp.Failure.class,
            () -> source.getHistoryWindow("USDJPY", "1m", 2, "Forex", 1700000400000L, 1700000519999L)).getMessage());
        when(http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"chart\":{\"result\":\"bad\",\"error\":null}}"));
        assertThrows(MarketHttp.Failure.class, () -> source.getHistoryWindow("USDJPY", "1m", 2, "Forex", 1700000400000L, 1700000519999L));
    }
    @Test void yahooNullSessionSlotsDoNotTurnIntoInventedZeroVolumeCandles() {
        MarketQuoteSource source = new MarketQuoteSource(); MarketHttp http = mock(MarketHttp.class);
        ReflectionTestUtils.setField(source, "http", http); ReflectionTestUtils.setField(source, "yahooUrl", "https://query1.finance.yahoo.com/v8/finance");
        when(http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"chart\":{\"error\":null,\"result\":[{\"timestamp\":[1700000400,1700000460],\"indicators\":{\"quote\":[{\"open\":[null,100],\"high\":[null,101],\"low\":[null,99],\"close\":[null,100],\"volume\":[null,null]}]}}]}}"));
        List<Map<String,Object>> rows = ControlHistoryStore.rows(source.getHistoryWindow("AAPL", "1m", 2, "US", 1700000400000L, 1700000519999L));
        assertEquals(1, rows.size()); assertNull(rows.get(0).get("volume")); assertFalse(SourceHistoryGapRepair.valid(rows.get(0)));
    }
    @Test void sourceFetchRejectsBothReadAndWriteTransactionsBeforeAnyHttp() {
        MarketQuoteSource source = new MarketQuoteSource(); MarketHttp http = mock(MarketHttp.class); ReflectionTestUtils.setField(source, "http", http);
        DriverManagerDataSource data = new DriverManagerDataSource("jdbc:h2:mem:network_guard_" + UUID.randomUUID(), "sa", "");
        for (boolean readOnly : List.of(true, false)) {
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(data)); transaction.setReadOnly(readOnly);
            assertThrows(IllegalStateException.class, () -> transaction.execute(status -> source.getHistoryWindow("AAPL", "1m", 2, "US", 1700000400000L, 1700000519999L)));
        }
        verifyNoInteractions(http);
    }
    @Test void historicalYahooPeriodMustBeNativeEvenWhenCompatibilityConverterAcceptsIt() {
        MarketQuoteSource source = new MarketQuoteSource(); MarketHttp http = mock(MarketHttp.class);
        ReflectionTestUtils.setField(source, "http", http); ReflectionTestUtils.setField(source, "yahooUrl", "https://query1.finance.yahoo.com/v8/finance");
        for (String period : List.of("3m", "2h", "4h", "6h", "12h")) assertEquals("unsupported_source_period",
            assertThrows(MarketHttp.Failure.class, () -> source.getHistoryWindow("AAPL", period, 2, "US", 1700000400000L, 1700000519999L)).getMessage());
        verifyNoInteractions(http);
        when(http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"chart\":{\"error\":null,\"result\":[{\"timestamp\":null}]}}"));
        source.getHistoryWindow("AAPL", "1h", 2, "US", 1700000400000L, 1700000519999L);
        verify(http).get(argThat(uri -> uri.toString().contains("interval=60m")));
    }
}
