package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.simulation.SimulationEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimulationMarketRelayTest {
    static final String KEY="12345678901234567890123456789012";
    MockHttpServletResponse call(String key,String method,String tenant,String body,boolean demo) throws Exception {
        MockHttpServletRequest request=new MockHttpServletRequest(method,"/api/simulation/market-feed");
        if(key!=null)request.addHeader("X-Simulation-Inspection-Key",key);
        request.addHeader("X-Simulation-Tenant-Id",tenant);request.setContent(body.getBytes("UTF-8"));
        MockHttpServletResponse response=new MockHttpServletResponse();SimulationEnvironment environment=mock(SimulationEnvironment.class);
        when(environment.enabled()).thenReturn(demo);JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM tenant WHERE id=?"),eq(Integer.class),anyLong())).thenReturn(0);
        SimulationMarketRelay.handle(request,response,environment,KEY,jdbc,new ObjectMapper());return response;
    }
    @Test void anonymousBearerAndWrongServiceKeyCannotReadCanonicalMarket() throws Exception {
        assertEquals(403,call(null,"POST","1","{}",false).getStatus());
        assertEquals(403,call("Bearer user-token","POST","1","{}",false).getStatus());
        assertEquals(403,call("wrong","POST","1","{}",false).getStatus());
        assertEquals(405,call(KEY,"GET","1","{}",false).getStatus());
    }
    @Test void unknownTenantAndInvalidBodyAreRejectedBeforeContextOrMarketAccess() throws Exception {
        assertEquals(400,call(KEY,"POST","2","{\"op\":\"snapshot\"}",false).getStatus());
        assertEquals(400,call(KEY,"POST","-1","{}",false).getStatus());
        assertEquals(400,call(KEY,"POST","bad","{}",false).getStatus());
        assertEquals("no-store",call(null,"POST","1","{}",false).getHeader("Cache-Control"));
    }
}
