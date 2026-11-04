package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.repository.OperationLogRepository;
import com.gtcfesk.exchange.simulation.SimulationEnvironment;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class AdminSimulationOrderTest {
    AdminPermissionService permissions=mock(AdminPermissionService.class);SimulationEnvironment environment=mock(SimulationEnvironment.class);
    AdminUserRepository admins=mock(AdminUserRepository.class);OperationLogRepository logs=mock(OperationLogRepository.class);
    ControlAuditService audit=mock(ControlAuditService.class);ObjectMapper mapper=new ObjectMapper();
    AdminSimulationOrderController controller=new AdminSimulationOrderController(permissions,environment,admins,logs,audit,mapper);
    @BeforeEach void setup(){login("ROLE_ADMIN");ReflectionTestUtils.setField(controller,"key","12345678901234567890123456789012");AdminUser admin=new AdminUser();admin.setId(91L);admin.setEmail("operator@example.test");admin.setEnabled(true);when(admins.findByTenantIdAndId(1L,91L)).thenReturn(Optional.of(admin));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void login(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("91",null,Collections.singletonList(new SimpleGrantedAuthority(role))));}
    ControlledExitService.Input input(){ControlledExitService.Input input=new ControlledExitService.Input();input.requestId="close-01234567890123456789";input.reason="模拟平仓";return input;}
    @Test void agentUserWrongEnvironmentAndMissingPermissionNeverReachTransport(){
        for(String role:Arrays.asList("ROLE_AGENT","ROLE_USER")){login(role);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->controller.close("contract",7L,input()));}
        login("ROLE_ADMIN");when(environment.enabled()).thenReturn(true);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->controller.close("contract",7L,input()));
        when(environment.enabled()).thenReturn(false);doThrow(new org.springframework.security.access.AccessDeniedException("denied")).when(permissions).require("orders","close_order");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->controller.close("contract",7L,input()));verifyNoInteractions(logs,audit);
    }
    @Test void unsupportedTargetsCallerSuppliedPricesAndInactiveOperatorsAreRejected(){
        ControlledExitService.Input input=input();input.takeProfit=java.math.BigDecimal.ONE;
        assertThrows(IllegalArgumentException.class,()->controller.close("contract",7L,input));
        assertThrows(IllegalArgumentException.class,()->controller.close("loan",7L,input()));
        assertThrows(IllegalArgumentException.class,()->controller.close("contract",0L,input()));
        when(admins.findByTenantIdAndId(1L,91L)).thenReturn(Optional.empty());assertThrows(org.springframework.security.access.AccessDeniedException.class,()->controller.close("contract",7L,input()));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->mapper.readValue("{\"requestId\":\"close-01234567890123456789\",\"closePrice\":1}",ControlledExitService.Input.class));
    }
    @Test void dedicatedTransportCarriesCanonicalActorAndNeverChangesRequestIdOrFallsBack() throws Exception {
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();java.util.concurrent.atomic.AtomicBoolean invalid=new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/api/simulation/order-close",exchange->{
            requests.incrementAndGet();Map<?,?> command=mapper.readValue(exchange.getRequestBody(),Map.class);
            assertEquals("12345678901234567890123456789012",exchange.getRequestHeaders().getFirst("X-Simulation-Inspection-Key"));assertEquals("1",exchange.getRequestHeaders().getFirst("X-Simulation-Tenant-Id"));
            assertEquals(91,command.get("actorId"));assertEquals("ADMIN",command.get("actorType"));assertEquals("operator@example.test",command.get("actorEmail"));
            assertEquals(input().requestId,command.get("requestId"));assertEquals("contract",command.get("kind"));assertEquals(7,command.get("orderId"));assertFalse(command.containsKey("userId"));assertFalse(command.containsKey("closePrice"));
            Map<String,Object> body=new LinkedHashMap<>();body.put("success",true);body.put("accountMode",invalid.get()?"REAL":"DEMO");body.put("tenantId",1);body.put("orderId",7);body.put("kind","contract");body.put("requestId",command.get("requestId"));body.put("order",Collections.singletonMap("profit",new java.math.BigDecimal("1234567890.1234567890123456")));
            byte[] bytes=mapper.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("X-Account-Environment","DEMO");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            ReflectionTestUtils.setField(controller,"url","http://127.0.0.1:"+server.getAddress().getPort()+"/api/simulation/inspection");
            org.springframework.http.ResponseEntity<?> reply=controller.close("contract",7L,input());assertEquals(200,reply.getStatusCodeValue());
            assertEquals(new java.math.BigDecimal("1234567890.1234567890123456"),((Map<?,?>)((Map<?,?>)reply.getBody()).get("order")).get("profit"));
            verify(logs).saveAndFlush(argThat(log -> log.getAdminId()==91L && log.getTargetType().equals("DEMO:contract") && log.getRequestParams().contains("1234567890.1234567890123456")));
            invalid.set(true);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.close("contract",7L,input()));assertEquals(2,requests.get());
        } finally {server.stop(0);}
    }
}
