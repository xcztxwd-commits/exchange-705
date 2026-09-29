package com.gtcfesk.exchange.simulation;
import com.gtcfesk.exchange.admin.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminAccountQueryPermissionTest {
 AdminPermissionService permissions=mock(AdminPermissionService.class);
 SimulationEnvironment environment=mock(SimulationEnvironment.class);
 AdminAccountQueryController controller=new AdminAccountQueryController(permissions,environment);
 @BeforeEach void setup(){ReflectionTestUtils.setField(controller,"key","");login("ROLE_ADMIN");}
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 void login(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1",null,Collections.singletonList(new SimpleGrantedAuthority(role))));}
 AdminReadRoutes.Query query(String method,String path){AdminReadRoutes.Query q=new AdminReadRoutes.Query();q.method=method;q.path=path;return q;}
 @Test void agentCannotUseGlobalDemoQueries(){login("ROLE_AGENT");assertThrows(AccessDeniedException.class,()->controller.query(query("GET","/api/admin/users"),new MockHttpServletResponse()));verifyNoInteractions(permissions);}
 @Test void livePermissionsCheckedBeforeTransport(){doThrow(new AccessDeniedException("denied")).when(permissions).require("users","wallet_management");assertThrows(AccessDeniedException.class,()->controller.query(query("GET","/api/admin/wallet/1/bank-cards"),new MockHttpServletResponse()));verify(permissions).require("users","wallet_management");}
 @Test void exportRequiresBothExportAndView(){assertThrows(ResponseStatusException.class,()->controller.query(query("GET","/api/admin/deposit/orders/export"),new MockHttpServletResponse()));verify(permissions).require("deposit_orders","export_deposit_orders");verify(permissions).require("deposit_orders","view_deposit_orders");}
 @Test void inboxDoesNotExpandRegularAdministratorSenderScope(){assertThrows(AccessDeniedException.class,()->controller.query(query("GET","/api/admin/support/inbox"),new MockHttpServletResponse()));}
 @Test void proxyPreservesCsvAndRequiresDemoMarker() throws Exception {
  com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  java.util.concurrent.atomic.AtomicBoolean marker=new java.util.concurrent.atomic.AtomicBoolean(true);
  server.createContext("/api/simulation/admin-query",exchange->{
   assertEquals("12345678901234567890123456789012",exchange.getRequestHeaders().getFirst("X-Simulation-Inspection-Key"));
   exchange.getResponseHeaders().set("Content-Type","text/csv;charset=UTF-8");
   if(marker.get())exchange.getResponseHeaders().set("X-Account-Environment","DEMO");
   byte[] body="id,amount\r\n1,0.10\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
   exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
  });server.start();
  try {
   ReflectionTestUtils.setField(controller,"key","12345678901234567890123456789012");ReflectionTestUtils.setField(controller,"url","http://127.0.0.1:"+server.getAddress().getPort()+"/api/simulation/inspection");
   org.springframework.http.ResponseEntity<?> r=(org.springframework.http.ResponseEntity<?>)controller.query(query("GET","/api/admin/deposit/orders/export"),new MockHttpServletResponse());
   assertTrue(r.getHeaders().getContentType().toString().startsWith("text/csv"));assertArrayEquals("id,amount\r\n1,0.10\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),(byte[])r.getBody());
   marker.set(false);ResponseStatusException failure=assertThrows(ResponseStatusException.class,()->controller.query(query("GET","/api/admin/users"),new MockHttpServletResponse()));assertEquals(503,failure.getStatus().value());
  } finally {server.stop(0);}
 }
 @Test void malformedAndMutationRequestsRejected(){assertThrows(IllegalArgumentException.class,()->controller.query(query("POST","/api/admin/users/1/status"),new MockHttpServletResponse()));assertThrows(IllegalArgumentException.class,()->AdminReadRoutes.permission("GET",null));AdminReadRoutes.Query q=query("GET","/api/admin/users");q.params=null;assertThrows(IllegalArgumentException.class,()->controller.query(q,new MockHttpServletResponse()));}
}
