package com.gtcfesk.exchange.security;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class OutboundEndpointPolicyTest {
 static class Policy extends OutboundEndpointPolicy {String address="9.9.9.9";boolean mixed;int calls;@Override protected InetAddress[] resolve(String h)throws UnknownHostException{calls++;return mixed?new InetAddress[]{InetAddress.getByName(address),InetAddress.getByName("127.0.0.1")}:new InetAddress[]{InetAddress.getByName(address)};}}
 Policy configured(){Policy p=new Policy();ReflectionTestUtils.setField(p,"smtpEndpoints","smtp.example.com:465,smtp.example.com:587");ReflectionTestUtils.setField(p,"callbackOrigins","https://callback.example.com:8443");return p;}
 @Test void emptyOperatorAllowlistRejectsBackendDestinationsBeforeDns(){Policy p=new Policy();assertThrows(RuntimeException.class,()->p.smtp("smtp.example.com","465"));assertThrows(RuntimeException.class,()->p.https("https://callback.example.com","callback"));assertEquals(0,p.calls);assertNotNull(p.https("https://support.example.com/chat","support"));assertEquals(1,p.calls);}
 @Test void supportAcceptsPublicHttpsWithoutGrantingBackendDestinations(){Policy p=configured();assertEquals(465,p.smtp("SMTP.EXAMPLE.COM","465").port);for(String url:Arrays.asList("https://linkschatin.com/agent/chat.html?key=test","https://other.example.com:8443/chat","https://callback.example.com:8443/"))assertDoesNotThrow(()->p.validateConfig("customer.service.link",url));assertNotNull(p.https("https://callback.example.com:8443/callback","callback"));for(String url:Arrays.asList("http://support.example.com","https://a@support.example.com","https://support.example.com/#fragment","file:///etc/passwd"))assertThrows(RuntimeException.class,()->p.https(url,"support"));for(String url:Arrays.asList("https://other.example.com/callback","https://callback.example.com:443/callback"))assertThrows(RuntimeException.class,()->p.https(url,"callback"));assertThrows(RuntimeException.class,()->p.https("https://support.example.com","unknown"));assertThrows(RuntimeException.class,()->p.smtp("smtp.example.com","25"));assertThrows(RuntimeException.class,()->p.smtp("smtp.example.com.","465"));}
 @Test void privateSpecialMappedAndMixedDnsAllRejected()throws Exception{Policy p=configured();for(String address:Arrays.asList("0.0.0.0","127.0.0.1","10.0.0.1","172.16.0.1","192.168.1.1","169.254.169.254","100.64.0.1","198.19.1.1","192.0.2.1","224.0.0.1","::1","fe80::1","fc00::1","::ffff:127.0.0.1","2001:db8::1","64:ff9b::a00:1")){assertFalse(OutboundEndpointPolicy.publicAddress(InetAddress.getByName(address)),address);p.address=address;assertThrows(RuntimeException.class,()->p.smtp("smtp.example.com","465"));}p.address="9.9.9.9";p.mixed=true;assertThrows(RuntimeException.class,()->p.https("https://support.example.com","support"));assertTrue(OutboundEndpointPolicy.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));}
 @Test void callbackAndSmtpConfigurationCannotBypassSendBoundary(){Policy p=configured();assertThrows(RuntimeException.class,()->p.validateConfig("payment.callback_url","http://localhost/foo"));assertThrows(RuntimeException.class,()->p.validateConfig("mail.host","127.0.0.1"));assertThrows(RuntimeException.class,()->p.validateConfig("mail.port","nonsense"));assertDoesNotThrow(()->p.validateConfig("customer.service.link",""));}

 @Test void localLoopbackRequiresBothExplicitOptInAndExactSmtpAuthorization(){
  Policy p=configured();ReflectionTestUtils.setField(p,"localLoopbackEndpoints","smtp.localhost:465,smtp587.localhost:587,a.localhost:443");
  ReflectionTestUtils.setField(p,"smtpEndpoints","smtp.localhost:465,smtp587.localhost:587");
  assertThrows(RuntimeException.class,()->p.smtp("smtp.localhost","465"));assertThrows(RuntimeException.class,()->p.routingCheck("a.localhost"));assertEquals(0,p.calls);
  ReflectionTestUtils.setField(p,"localLoopbackEnabled",true);
  assertEquals("127.0.0.1",p.smtp("SMTP.LOCALHOST","465").address.getHostAddress());
  assertEquals("127.0.0.1",p.smtp("smtp587.localhost","587").address.getHostAddress());
  assertDoesNotThrow(()->p.smtpHost("smtp.localhost"));assertDoesNotThrow(()->p.smtpHost("smtp587.localhost"));assertEquals(0,p.calls);
  assertThrows(RuntimeException.class,()->p.smtp("smtp.localhost","587"));assertThrows(RuntimeException.class,()->p.smtp("smtp.localhost.evil.test","465"));
  ReflectionTestUtils.setField(p,"smtpEndpoints","");assertThrows(RuntimeException.class,()->p.smtp("smtp.localhost","465"));
 }
 @Test void localOptInNeverGrantsPrivateRangesSupportOrCallbacks(){
  Policy p=configured();ReflectionTestUtils.setField(p,"localLoopbackEnabled",true);ReflectionTestUtils.setField(p,"localLoopbackEndpoints","smtp.localhost:465,10.0.0.1:465,smtp.example.com:465,support.localhost:443");
  ReflectionTestUtils.setField(p,"smtpEndpoints","smtp.localhost:465,10.0.0.1:465,smtp.example.com:465");p.address="127.0.0.1";
  assertThrows(RuntimeException.class,()->p.smtp("10.0.0.1","465"));assertThrows(RuntimeException.class,()->p.smtp("smtp.example.com","465"));
  ReflectionTestUtils.setField(p,"callbackOrigins","https://support.localhost");
  assertThrows(RuntimeException.class,()->p.https("https://support.localhost","support"));assertThrows(RuntimeException.class,()->p.https("https://support.localhost","callback"));
  assertThrows(RuntimeException.class,()->p.routingCheck("unknown.localhost"));assertThrows(RuntimeException.class,()->p.routingCheck("a.localhost", "invalid"));
 }

 @Test void localSmtpHostValidationUsesAuthorizedLocalPortInsteadOfOtherConfiguredPort(){
  Policy p=configured();ReflectionTestUtils.setField(p,"localLoopbackEnabled",true);ReflectionTestUtils.setField(p,"localLoopbackEndpoints","smtp.localhost:587");ReflectionTestUtils.setField(p,"smtpEndpoints","smtp.localhost:465,smtp.localhost:587");
  assertDoesNotThrow(()->p.validateConfig("mail.host","smtp.localhost"));assertEquals(0,p.calls);assertEquals("127.0.0.1",p.smtp("smtp.localhost","587").address.getHostAddress());
 }
 @Test void alternateRoutingPortRequiresExplicitNamedLocalPinWithoutChangingPublicDestinations(){
  Policy p=configured();ReflectionTestUtils.setField(p,"localRoutingPort",8443);ReflectionTestUtils.setField(p,"localLoopbackEndpoints","a.localhost:8443");
  assertEquals(443,p.routingPort("a.localhost"));ReflectionTestUtils.setField(p,"localLoopbackEnabled",true);
  assertEquals("https://a.localhost:8443",p.routingOrigin("a.localhost"));assertEquals("https://public.example.com",p.routingOrigin("public.example.com"));assertEquals("https://b.localhost",p.routingOrigin("b.localhost"));
  assertEquals(8443,p.routingPort("a.localhost"));assertEquals(443,p.routingPort("b.localhost"));assertEquals(443,p.routingPort("public.example.com"));assertEquals(443,p.routingPort("127.0.0.1"));
  ReflectionTestUtils.setField(p,"localRoutingPort",0);assertThrows(RuntimeException.class,()->p.routingPort("a.localhost"));
 }
}
