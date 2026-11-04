package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.simulation.AdminReadRoutes;
import org.junit.jupiter.api.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class AdminSimulationIdentityTest {
    UserAccountRepository users=mock(UserAccountRepository.class);
    KycRecordRepository identities=mock(KycRecordRepository.class);
    SystemConfigService configs=mock(SystemConfigService.class);
    ObjectMapper mapper=new ObjectMapper();
    AdminSimulationIdentity service=new AdminSimulationIdentity(users,identities,configs,mapper);
    @BeforeEach void setup() {
        UserAccount user=new UserAccount();user.setId(7L);user.setTenantId(1L);user.setEmail("real@example.test");
        user.setNickname("正式昵称");user.setRemark("共享备注");user.setLastLoginDomain("trade.example.test");
        user.setKycStatus("VERIFIED");user.setPasswordHash("PRIVATE_HASH");user.setCurrentToken("PRIVATE_SESSION");
        when(users.findAllByTenantIdAndIdIn(eq(1L),anyCollection())).thenReturn(Collections.singletonList(user));
        when(users.findByTenantIdAndParentUserId(1L,7L)).thenReturn(Collections.emptyList());
        when(configs.getConfigValue("domain.whitelist")).thenReturn("trade.example.test");
        KycRecord record=new KycRecord();record.setRealName("正式姓名");
        when(identities.findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(1L,7L)).thenReturn(Optional.of(record));
    }
    byte[] json(String body){return body.getBytes(StandardCharsets.UTF_8);}
    @Test void userProfileAndLabelsMatchRealIdentityWhileDemoFundsAndVersionStayIndependent() throws Exception {
        byte[] output=service.canonicalize(json("{\"list\":[{\"id\":7,\"email\":\"demo@example.invalid\",\"nickname\":\"Demo user\",\"remark\":null,\"realName\":null,\"kycStatus\":\"NOT_VERIFIED\",\"userTypeLabel\":\"假人\",\"rowVersion\":5,\"fundBalance\":1234567890.1234567890123456}]}"));
        Map<?,?> row=(Map<?,?>)((List<?>)mapper.readValue(output,Map.class).get("list")).get(0);
        assertEquals("real@example.test",row.get("email"));assertEquals("正式昵称",row.get("nickname"));
        assertEquals("共享备注",row.get("remark"));assertEquals("正式姓名",row.get("realName"));
        assertEquals("VERIFIED",row.get("kycStatus"));assertEquals("真人",row.get("userTypeLabel"));
        assertEquals(5,row.get("rowVersion"));
        assertEquals(new java.math.BigDecimal("1234567890.1234567890123456"),mapper.copy()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(output).get("list").get(0).get("fundBalance").decimalValue());
        assertFalse(new String(output,StandardCharsets.UTF_8).contains("PRIVATE_"));
        verify(users).findAllByTenantIdAndIdIn(eq(1L),eq(Collections.singleton(7L)));
    }
    @Test void nestedOrdersAndLookupUseSameEmailAndRemarksWithoutReplacingOrderIdsOrProfits() throws Exception {
        Map<?,?> result=mapper.readValue(service.canonicalize(json("{\"orders\":[{\"id\":99,\"userId\":7,\"userEmail\":\"old\",\"userRemark\":null,\"profit\":\"-42.01\"}],\"lookup\":[{\"userId\":7,\"email\":\"old\"}]}")),Map.class);
        Map<?,?> order=(Map<?,?>)((List<?>)result.get("orders")).get(0);
        assertEquals(99,order.get("id"));assertEquals("-42.01",order.get("profit"));
        assertEquals("real@example.test",order.get("userEmail"));assertEquals("共享备注",order.get("userRemark"));
        assertEquals("real@example.test",((Map<?,?>)((List<?>)result.get("lookup")).get(0)).get("email"));
    }
    @Test void livePriceOnlyResponsesDoNotTriggerProfileQueriesAndDetailsStayReadOnly() throws Exception {
        byte[] body=json("{\"list\":[{\"id\":99,\"profit\":\"42.00\"}]}");
        assertArrayEquals(body,service.canonicalize(body));verifyNoInteractions(users,identities,configs);
        assertEquals("users:detail",AdminReadRoutes.permission("GET","/api/admin/users/7/profile"));
        assertThrows(IllegalArgumentException.class,()->AdminReadRoutes.permission("POST","/api/admin/users/7/profile"));
    }
}
