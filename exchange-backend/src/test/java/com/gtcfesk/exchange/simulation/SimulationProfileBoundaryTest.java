package com.gtcfesk.exchange.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class SimulationProfileBoundaryTest {
    static final String KEY="12345678901234567890123456789012";
    JdbcTemplate jdbc; SimulationEnvironment environment; SimulationProfileBoundary boundary;
    ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        List<String> definitions=new ArrayList<>();
        for(String column:SimulationProfileBoundary.COLUMNS) definitions.add(column+" varchar(500)");
        jdbc.execute("CREATE TABLE user_account(tenant_id bigint,id bigint,password_hash varchar(128),current_token varchar(128),"+String.join(",",definitions)+")");
        jdbc.update("INSERT INTO user_account(tenant_id,id,email,nickname,remark,password_hash,current_token) VALUES(1,7,'real@example.test','真实昵称','真实备注','PRIVATE_HASH','PRIVATE_TOKEN'),(2,7,'other@example.test','其它租户','不可泄漏',null,null)");
        environment=mock(SimulationEnvironment.class);boundary=new SimulationProfileBoundary(environment,jdbc,mapper);
        ReflectionTestUtils.setField(boundary,"key",KEY);
    }
    MockHttpServletResponse call(String method,String key,String tenant,String body) throws Exception {
        MockHttpServletRequest request=new MockHttpServletRequest(method,"/api/simulation/user-profiles");
        if(key!=null)request.addHeader("X-Simulation-Inspection-Key",key);
        request.addHeader("X-Simulation-Tenant-Id",tenant);request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response=new MockHttpServletResponse();boundary.doFilter(request,response,new MockFilterChain());return response;
    }
    @Test void canonicalEmailNicknameAndInternalRemarkRemainServerOnlyAndTenantScoped() throws Exception {
        MockHttpServletResponse response=call("POST",KEY,"1","[7]");
        assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        Map<?,?> result=mapper.readValue(response.getContentAsString(),Map.class);
        Map<?,?> profile=(Map<?,?>)((List<?>)result.get("list")).get(0);
        assertEquals("REAL",result.get("environment"));assertEquals("real@example.test",profile.get("email"));
        assertEquals("真实昵称",profile.get("nickname"));assertEquals("真实备注",profile.get("remark"));
        assertFalse(response.getContentAsString().contains("PRIVATE_"));assertFalse(response.getContentAsString().contains("其它租户"));
        assertFalse(profile.containsKey("password_hash"));assertFalse(profile.containsKey("current_token"));
    }
    @Test void userBearerOrWrongKeyCannotReadInternalRemarksAndDemoCannotServeCanonicalProfiles() throws Exception {
        assertEquals(403,call("POST",null,"1","[7]").getStatus());assertEquals(403,call("POST","wrong","1","[7]").getStatus());
        when(environment.enabled()).thenReturn(true);assertEquals(403,call("POST",KEY,"1","[7]").getStatus());
    }
    @Test void batchesAreBoundedAndMalformedIdsAndTenantsAreRejected() throws Exception {
        assertEquals(405,call("GET",KEY,"1","[7]").getStatus());
        for(String body:Arrays.asList("[]","[0]","[-1]","[\"7 OR 1=1\"]",mapper.writeValueAsString(Collections.nCopies(101,7))))
            assertEquals(400,call("POST",KEY,"1",body).getStatus());
        assertEquals(400,call("POST",KEY,"0","[7]").getStatus());
        assertEquals(400,call("POST",KEY,"invalid","[7]").getStatus());
    }
}
