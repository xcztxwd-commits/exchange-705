package com.gtcfesk.exchange.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.ControlledExitService;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class SimulationOrderCloseBoundaryTest {
    static final String KEY="12345678901234567890123456789012";
    ObjectMapper mapper=new ObjectMapper();SimulationEnvironment environment=mock(SimulationEnvironment.class);
    ControlledExitService service=mock(ControlledExitService.class);SimulationOrderCloseBoundary boundary;
    @BeforeEach void setup() { when(environment.enabled()).thenReturn(true);boundary=new SimulationOrderCloseBoundary(environment,service,mapper);ReflectionTestUtils.setField(boundary,"key",KEY); }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();assertNull(SimulationOrderCloseBoundary.current());}
    Map<String,Object> command(){Map<String,Object> body=new LinkedHashMap<>();body.put("kind","contract");body.put("orderId",7);body.put("actorId",91);body.put("actorType","ADMIN");body.put("actorEmail","operator@example.test");body.put("requestId","close-01234567890123456789");body.put("reason","模拟平仓");return body;}
    MockHttpServletResponse call(String method,String key,String tenant,Object body) throws Exception {
        MockHttpServletRequest request=new MockHttpServletRequest(method,"/api/simulation/order-close");
        if(key!=null)request.addHeader("X-Simulation-Inspection-Key",key);request.addHeader("X-Simulation-Tenant-Id",tenant);
        request.setContent(mapper.writeValueAsBytes(body));MockHttpServletResponse response=new MockHttpServletResponse();
        boundary.doFilter(request,response,new MockFilterChain());return response;
    }
    @Test void keyModeMethodAndStrictCommandAreRequiredBeforeAnySettlement() throws Exception {
        assertEquals(403,call("POST",null,"1",command()).getStatus());assertEquals(403,call("POST","wrong","1",command()).getStatus());
        assertEquals(405,call("GET",KEY,"1",command()).getStatus());assertEquals(400,call("POST",KEY,"0",command()).getStatus());
        for(String field:Arrays.asList("userId","closePrice","profit","accountMode","path")) {
            Map<String,Object> body=command();body.put(field,"untrusted");assertEquals(400,call("POST",KEY,"1",body).getStatus());
        }
        for(String kind:Arrays.asList("loan","financial","contract/../option")) {Map<String,Object> body=command();body.put("kind",kind);assertEquals(400,call("POST",KEY,"1",body).getStatus());}
        when(environment.enabled()).thenReturn(false);assertEquals(403,call("POST",KEY,"1",command()).getStatus());verifyNoInteractions(service);
    }
    @Test void delegationIsBoundToExactlyOneTenantOrderKindAndRequestThenAlwaysCleared() throws Exception {
        UsernamePasswordAuthenticationToken original=new UsernamePasswordAuthenticationToken("original",null,Collections.emptyList());SecurityContextHolder.getContext().setAuthentication(original);
        when(service.execute(eq("contract"),eq(7L),eq("close"),any())).thenAnswer(invocation->{
            ControlledExitService.Input input=invocation.getArgument(3);SimulationOrderCloseBoundary.Proof proof=SimulationOrderCloseBoundary.current();
            assertNotNull(proof);assertEquals(91L,proof.actorId);assertEquals(1L,TenantContext.requireTenantId());
            proof.require(1L,"contract",7L,"close",input);
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->proof.require(2L,"contract",7L,"close",input));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->proof.require(1L,"contract",8L,"close",input));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->proof.require(1L,"option",7L,"close",input));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->proof.require(1L,"contract",7L,"cancel",input));
            return Collections.singletonMap("success",true);
        });
        MockHttpServletResponse response=call("POST",KEY,"1",command());assertEquals(200,response.getStatus());
        Map<?,?> body=mapper.readValue(response.getContentAsString(),Map.class);assertEquals("DEMO",body.get("accountMode"));assertEquals("DEMO",response.getHeader("X-Account-Environment"));
        assertSame(original,SecurityContextHolder.getContext().getAuthentication());assertNull(SimulationOrderCloseBoundary.current());
        assertEquals(1L,TenantContext.requireTenantId());
    }
    @Test void failedSettlementDoesNotLeaveAnAuthorizedThreadOrFakeSuccess() throws Exception {
        when(service.execute(anyString(),anyLong(),anyString(),any())).thenThrow(new BusinessException("行情暂不可用"));
        MockHttpServletResponse response=call("POST",KEY,"1",command());assertEquals(400,response.getStatus());
        assertFalse((Boolean)mapper.readValue(response.getContentAsString(),Map.class).get("success"));assertNull(SimulationOrderCloseBoundary.current());
    }
}
