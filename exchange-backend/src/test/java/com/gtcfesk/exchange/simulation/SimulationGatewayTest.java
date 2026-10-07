package com.gtcfesk.exchange.simulation;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;
@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class SimulationGatewayTest {
 SimulationGateway gateway;MockRestServiceServer server;
 @BeforeEach void setup(){gateway=new SimulationGateway();ReflectionTestUtils.setField(gateway,"identityUrl","http://identity.test/api/simulation");com.gtcfesk.exchange.control.TenantRepository tenants=org.mockito.Mockito.mock(com.gtcfesk.exchange.control.TenantRepository.class);com.gtcfesk.exchange.control.Tenant tenant=new com.gtcfesk.exchange.control.Tenant();tenant.setId(1L);tenant.setFrontendHost("a.example.test");tenant.setDomainVerified(true);org.mockito.Mockito.when(tenants.findById(1L)).thenReturn(java.util.Optional.of(tenant));ReflectionTestUtils.setField(gateway,"tenants",tenants);server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(gateway,"http")).build();}
 @Test void validatesExistingRealSessionAndExplicitEnvironment(){
  server.expect(requestTo("http://a.example.test/api/simulation/session")).andExpect(header("Authorization","Bearer token")).andExpect(header("X-Forwarded-Host","a.example.test")).andExpect(header("X-Account-Mode","REAL")).andRespond(withSuccess("{\"environment\":\"REAL\",\"tenantId\":1,\"userId\":7}",MediaType.APPLICATION_JSON));
  assertEquals(7L,gateway.authenticate("Bearer token"));server.verify();
 }
 @Test void expiredSessionNeverFallsBackToLocalIdentity(){
  server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED));assertThrows(org.springframework.web.client.HttpClientErrorException.class,()->gateway.authenticate("Bearer revoked"));server.verify();
 }
 @Test void demoCannotAuthenticateItself(){
  server.expect(anything()).andRespond(withSuccess("{\"environment\":\"DEMO\",\"tenantId\":1,\"userId\":7}",MediaType.APPLICATION_JSON));assertThrows(IllegalStateException.class,()->gateway.authenticate("Bearer token"));server.verify();
 }
 @Test void realTransportSendsTenantHostToConfiguredIdentityServer() throws Exception {
  com.sun.net.httpserver.HttpServer identity=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  java.util.concurrent.atomic.AtomicReference<String> wireHost=new java.util.concurrent.atomic.AtomicReference<>();
  java.util.concurrent.atomic.AtomicReference<String> wireBearer=new java.util.concurrent.atomic.AtomicReference<>();
  com.gtcfesk.exchange.control.TenantHostService boundary=new com.gtcfesk.exchange.control.TenantHostService(org.mockito.Mockito.mock(com.gtcfesk.exchange.control.TenantRepository.class),"example.test","","","127.0.0.1/32");
  identity.createContext("/api/simulation/session",request->{
   org.springframework.mock.web.MockHttpServletRequest received=new org.springframework.mock.web.MockHttpServletRequest();received.setRemoteAddr("127.0.0.1");
   received.addHeader("Host",request.getRequestHeaders().getFirst("Host"));received.addHeader("X-Forwarded-Host",request.getRequestHeaders().getFirst("X-Forwarded-Host"));
   wireHost.set(boundary.host(received));wireBearer.set(request.getRequestHeaders().getFirst("Authorization"));
   byte[] body="{\"environment\":\"REAL\",\"tenantId\":1,\"userId\":7}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
   request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,body.length);request.getResponseBody().write(body);request.close();
  });
  identity.start();
  try {
   SimulationGateway actual=new SimulationGateway();ReflectionTestUtils.setField(actual,"tenants",ReflectionTestUtils.getField(gateway,"tenants"));
   ReflectionTestUtils.setField(actual,"identityUrl","http://127.0.0.1:"+identity.getAddress().getPort()+"/api/simulation");
   assertEquals(7L,actual.authenticate("Bearer wire-fixture"));assertEquals("a.example.test",wireHost.get());assertEquals("Bearer wire-fixture",wireBearer.get());
  } finally {identity.stop(0);}
 }
 @Test void httpsCannotUseAnUnrelatedHost(){
  ReflectionTestUtils.setField(gateway,"identityUrl","https://other.example.test/api/simulation");
  assertThrows(IllegalStateException.class,()->gateway.authenticate("Bearer token"));server.verify();
 }
}
