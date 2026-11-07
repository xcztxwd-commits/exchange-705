package com.gtcfesk.exchange.user;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AssetEquityStoreEncodingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AssetEquityStore store = new AssetEquityStore(mock(javax.sql.DataSource.class), mapper);

    @Test void legacyEvidenceRoundTripsUnicodeKeysValuesAndExactAmounts() throws Exception {
        Map<String,Object> quote = new LinkedHashMap<>();
        quote.put("marketReason", "外汇周末休市");
        quote.put("中文字段", "日元 ¥ / Ελληνικά / \uD83D\uDE80 / \\u4e2d");
        quote.put("price", new BigDecimal("0.0065321000000001"));
        quote.put("available", false);
        quote.put("missing", null);
        Map<String,Object> evidence = new LinkedHashMap<>();
        evidence.put("quotes", Collections.singletonMap("JPY=X", quote));
        evidence.put("rates", Arrays.asList(quote, Collections.emptyMap()));

        String encoded = store.json(evidence);

        assertTrue(encoded.chars().allMatch(c -> c < 128), "All persisted JSON must fit legacy latin1 columns");
        assertEquals(mapper.readTree(mapper.writeValueAsString(evidence)), mapper.readTree(encoded));
        assertEquals("外汇周末休市", mapper.readTree(encoded).path("quotes").path("JPY=X").path("marketReason").asText());
        assertTrue(encoded.contains("0.0065321000000001"), "Escaping must not change numeric precision");
    }

    @Test void persistenceDoesNotMutateSharedMapperOrApiEncoding() throws Exception {
        assertFalse(mapper.getFactory().isEnabled(JsonGenerator.Feature.ESCAPE_NON_ASCII));
        store.json(Collections.singletonMap("marketReason", "外汇周末休市"));
        assertFalse(mapper.getFactory().isEnabled(JsonGenerator.Feature.ESCAPE_NON_ASCII));
        assertTrue(mapper.writeValueAsString(Collections.singletonMap("message", "正常")).contains("正常"));
    }

    @Test void invalidEvidenceKeepsExistingFailureContract() {
        Object cyclic = new Object() { public Object getCycle() { return this; } };
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> store.json(cyclic));
        assertEquals("Invalid valuation evidence", failure.getMessage());
        assertNotNull(failure.getCause());
    }
}
