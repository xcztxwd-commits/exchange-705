package com.gtcfesk.exchange.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.support.SupportService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** v1 retention must use the same strict UTC microsecond representation as v2 archives. */
class ChatRetentionTimestampTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final ChatRetentionService service = new ChatRetentionService(mock(JdbcTemplate.class),
        mock(TenantRepository.class), mock(TenantPolicyRepository.class), mock(ControlAuditService.class), json);
    Map<String,Object> data(Object time) throws Exception {
        Map<String,Object> message=new HashMap<>();
        message.put("id",1L);message.put("sender","USER");message.put("sender_id",7L);
        message.put("sender_name","fixture");message.put("request_id","stage3-test");
        message.put("text","Synthetic");message.put("image",false);message.put("image_hash","");
        message.put("previous_hash","");message.put("created_at",time);
        String hash=SupportService.sha256(json.writeValueAsBytes(Arrays.asList(9L,"USER",7L,"fixture",
            "stage3-test","Synthetic","","2024-01-01T00:00:00.123456Z","")));
        message.put("hash",hash);
        Map<String,Object> data=new HashMap<>();data.put("messages",Collections.singletonList(message));
        data.put("conversation",Collections.singletonList(Collections.singletonMap("last_hash",hash)));
        data.put("attachments",Collections.emptyList());return data;
    }
    @Test void bothJdbcTypesKeepUtcAndAllSixFractionDigits() throws Exception {
        Instant instant=Instant.parse("2024-01-01T00:00:00.123456Z");
        for(Object time:Arrays.asList(Timestamp.from(instant),LocalDateTime.ofInstant(instant,ZoneOffset.UTC)))
            assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(service,"verifyMessageChain",2L,9L,data(time)));
    }
    @Test void invalidTypesAndDifferentMicrosecondsStillFailClosed() throws Exception {
        for(Object time:Arrays.asList(null,"2024-01-01T00:00:00.123456Z",0L,
                LocalDateTime.parse("2024-01-01T00:00:00.123457"))) {
            Map<String,Object> input=data(time);
            assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(service,"verifyMessageChain",2L,9L,input));
        }
    }
}
