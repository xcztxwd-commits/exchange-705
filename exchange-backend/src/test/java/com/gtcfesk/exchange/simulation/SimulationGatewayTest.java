package com.gtcfesk.exchange.simulation;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;
class SimulationGatewayTest {
 SimulationGateway gateway;MockRestServiceServer server;
 @BeforeEach void setup(){gateway=new SimulationGateway();ReflectionTestUtils.setField(gateway,"identityUrl","http://identity.test/api/simulation");server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(gateway,"http")).build();}
 @Test void validatesExistingRealSessionAndExplicitEnvironment(){
  server.expect(requestTo("http://identity.test/api/simulation/session")).andExpect(header("Authorization","Bearer token")).andExpect(header("X-Account-Mode","REAL")).andRespond(withSuccess("{\"environment\":\"REAL\",\"userId\":7}",MediaType.APPLICATION_JSON));
  assertEquals(7L,gateway.authenticate("Bearer token"));server.verify();
 }
 @Test void expiredSessionNeverFallsBackToLocalIdentity(){
  server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED));assertThrows(org.springframework.web.client.HttpClientErrorException.class,()->gateway.authenticate("Bearer revoked"));server.verify();
 }
 @Test void demoCannotAuthenticateItself(){
  server.expect(anything()).andRespond(withSuccess("{\"environment\":\"DEMO\",\"userId\":7}",MediaType.APPLICATION_JSON));assertThrows(IllegalStateException.class,()->gateway.authenticate("Bearer token"));server.verify();
 }
}
