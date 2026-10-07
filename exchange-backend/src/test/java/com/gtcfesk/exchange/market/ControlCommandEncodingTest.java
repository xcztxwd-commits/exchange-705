package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.OrderRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlCommandEncodingTest {
    private final ControlHistoryStore store = new ControlHistoryStore(mock(JdbcTemplate.class), mock(PlatformTransactionManager.class));
    private static void roundTrip(String message) {
        String stored = ControlHistoryStore.encodeCommandMessage(message);
        assertTrue(stored.length() <= 255, message);
        assertTrue(stored.chars().allMatch(c -> c < 128), message);
        assertEquals(message, ControlHistoryStore.decodeCommandMessage(stored));
    }
    @Test void reversibleMessagesPreserveLegacyPlainNullAndPrefixCollisions() {
        assertNull(ControlHistoryStore.encodeCommandMessage(null));
        assertNull(ControlHistoryStore.decodeCommandMessage(null));
        for (String message : Arrays.asList("Legacy cancelled", "café", "控制请求已取消", "emoji 🚀", "~mcc1~5o6n5Yi2")) roundTrip(message);
        assertEquals("Legacy cancelled", ControlHistoryStore.encodeCommandMessage("Legacy cancelled"));
        assertEquals("旧的明文错误", ControlHistoryStore.decodeCommandMessage("旧的明文错误"));
        assertEquals("café", ControlHistoryStore.decodeCommandMessage("café"));
        assertEquals("~mcc1~not*base64", ControlHistoryStore.decodeCommandMessage("~mcc1~not*base64"));
        assertEquals("~mcc1~/w==", ControlHistoryStore.decodeCommandMessage("~mcc1~/w=="));
    }
    @Test void overflowAndInvalidUtf16AreRejectedWithoutTruncation() {
        assertThrows(IllegalArgumentException.class, () -> ControlHistoryStore.encodeCommandMessage(String.join("", Collections.nCopies(256, "a"))));
        assertThrows(IllegalArgumentException.class, () -> ControlHistoryStore.encodeCommandMessage(String.join("", Collections.nCopies(63, "中"))));
        assertThrows(IllegalArgumentException.class, () -> ControlHistoryStore.encodeCommandMessage("\uD800"));
        roundTrip(String.join("", Collections.nCopies(255, "a")));
        roundTrip(String.join("", Collections.nCopies(62, "中")));
    }
    @Test void everyCurrentBoundedFailureAndParserMessageFitsNativeVarchar() throws Exception {
        // Source-derived regression: a newly enlarged static user-facing message must fail this bound.
        Pattern failure = Pattern.compile("new (?:BalancedControlPlan\\.)?Failure\\(\"[A-Z_]+\",\\s*\"([^\"]*)\"");
        int count = 0;
        for (String file : Arrays.asList("BalancedControlPlan", "StabilizedControlPlan", "TargetControlSettings", "ForexQuoteMarketService", "PersistentPriceControl", "MarketControlCommands", "ControlHistoryStore")) {
            String source = new String(Files.readAllBytes(Paths.get("src/main/java/com/gtcfesk/exchange/market/" + file + ".java")), StandardCharsets.UTF_8);
            Matcher matches = failure.matcher(source);
            while (matches.find()) { roundTrip(matches.group(1)); count++; }
            if (file.equals("TargetControlSettings")) {
                Matcher errors = Pattern.compile("(?:error|IllegalArgumentException)\\(\"([^\"]*)\"\\)").matcher(source);
                while (errors.find()) roundTrip("单步幅度公式第257字符：" + errors.group(1));
            }
        }
        assertTrue(count >= 40, "Failure source coverage unexpectedly shrank: " + count);
        for (String message : Arrays.asList("控制请求已取消", "控制准备已取消", "应急回源已取消准备", "控制命令有限重试已耗尽", "引擎暂不可用，等待有限退避", "启动未完成：DataIntegrityViolationException")) roundTrip(message);
    }
    @Test void longestActualFormulaFailureFitsAndDoesNotEchoUserFormula() {
        TargetControlOptions options = StabilizedControlPlanTest.options(String.join("", Collections.nCopies(256, "中")), "MANUAL", "1");
        BalancedControlPlan.Failure failure = assertThrows(BalancedControlPlan.Failure.class, () -> new TargetControlSettings(new BigDecimal("90"), new BigDecimal("91"), 20, 2, 1, options));
        assertEquals("INVALID_FORMULA", failure.code);
        assertEquals("单步幅度公式第257字符：未知变量或函数；允许start/target/gap/duration/intensity/tick/base及abs/min/max", failure.getMessage());
        roundTrip(failure.getMessage());
    }
    @Test void asciiJsonPreservesNewUnicodeNestedValuesAndLegacyDecodeWithoutChangingRequestHash() throws Exception {
        Map<String,Object> parameters = new TreeMap<>();parameters.put("stepFormula", "0.1 − 0");parameters.put("中文", Arrays.asList("🚀", "café"));
        String hash = OrderRequest.hash(parameters), encoded = store.encode(parameters);
        assertTrue(encoded.chars().allMatch(c -> c < 128));
        assertEquals(parameters, store.decode(encoded));
        assertEquals(parameters, store.decode(new ObjectMapper().writeValueAsString(parameters)));
        assertEquals(hash, OrderRequest.hash(store.decode(encoded)));
    }
    @Test void immutableHistoryRequestBytesKeepLegacyUnicodeHashWithoutTogglingSharedMapper() throws Exception {
        Map<String,Object> request = new TreeMap<>();request.put("code", "测试🚀");request.put("source", "历史来源");request.put("cursor", 1700000460000L);
        String legacy = new ObjectMapper().writeValueAsString(request);
        assertEquals(legacy, store.encodeHistoryRequest(request));
        assertEquals(HistoryOrdering.sha(legacy), HistoryOrdering.sha(store.encodeHistoryRequest(request)));
        assertNotEquals(legacy, store.encode(request));
        assertTrue(store.encode(request).chars().allMatch(c -> c < 128));
    }
    @Test void newAsciiAndLegacyUnicodePlanSnapshotsHaveIdenticalSemanticChecksum() throws Exception {
        TargetControlOptions options = StabilizedControlPlanTest.options("0.1 − 0", "MANUAL", "1");
        TargetControlPlan plan = StabilizedControlPlan.generate(StabilizedControlPlanTest.parameters(new BigDecimal("90.00"), new BigDecimal("91.00"), 20, 2, 1, options), 42);
        ControlHistoryStore.EncodedPlan encoded = store.encodePlan(plan);
        assertTrue(encoded.parameters.chars().allMatch(c -> c < 128));
        Map<String,Object> row = new LinkedHashMap<>();row.put("parameters_json", encoded.parameters);row.put("prices_json", encoded.prices);row.put("checksum", encoded.checksum);
        assertEquals(plan.checksum(), store.restorePlan(row).checksum());
        row.put("parameters_json", new ObjectMapper().writeValueAsString(plan.snapshot()));
        assertEquals(plan.checksum(), store.restorePlan(row).checksum());
    }
}
