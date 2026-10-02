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
  server.expect(requestTo("http://identity.test/api/simulation/session")).andExpect(header("Authorization","Bearer token")).andExpect(header("X-Account-Mode","REAL")).andRespond(withSuccess("{\"environment\":\"REAL\",\"tenantId\":1,\"userId\":7}",MediaType.APPLICATION_JSON));
  assertEquals(7L,gateway.authenticate("Bearer token"));server.verify();
 }
 @Test void expiredSessionNeverFallsBackToLocalIdentity(){
  server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED));assertThrows(org.springframework.web.client.HttpClientErrorException.class,()->gateway.authenticate("Bearer revoked"));server.verify();
 }
 @Test void demoCannotAuthenticateItself(){
  server.expect(anything()).andRespond(withSuccess("{\"environment\":\"DEMO\",\"tenantId\":1,\"userId\":7}",MediaType.APPLICATION_JSON));assertThrows(IllegalStateException.class,()->gateway.authenticate("Bearer token"));server.verify();
 }
}
