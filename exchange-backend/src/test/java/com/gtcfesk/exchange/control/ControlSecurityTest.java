package com.gtcfesk.exchange.control;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.tenant.TenantContext;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.LocalDateTime;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ControlSecurityTest {
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();TenantContext.clear();}
 @Test void hostNormalizationAndUntrustedForwarding(){
  assertEquals("a.example.com",TenantHostService.normalizeHost("A.Example.com:443"));
  for(String host:Arrays.asList("a.example.com.attacker.test/path","a.example.com@evil.test","a.example.com,evil.test","a.example.com.","a.example.com\n"))assertThrows(RuntimeException.class,()->TenantHostService.normalizeHost(host));
  TenantHostService hosts=new TenantHostService(mock(TenantRepository.class),"example.com","https://admin.example.com","https://control.example.com","");
  assertEquals("a.example.com",hosts.validateAssignment("a.example.com"));assertThrows(RuntimeException.class,()->hosts.validateAssignment("x.a.example.com"));assertThrows(RuntimeException.class,()->hosts.validateAssignment("admin.example.com"));
  MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("Host","a.example.com");request.addHeader("X-Forwarded-Host","b.example.com");assertThrows(RuntimeException.class,()->hosts.host(request));
 }
 @Test void totpUsesRfcVectorAndEncryptedStorage(){
  assertEquals("287082",ControlMfa.code("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",1));
  ControlMfa mfa=new ControlMfa(Base64.getEncoder().encodeToString(new byte[32]));String secret="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";String encrypted=mfa.encrypt(secret);assertNotEquals(secret,encrypted);assertEquals(secret,mfa.decrypt(encrypted));assertThrows(RuntimeException.class,()->new ControlMfa("").encrypt(secret));
 }
 @Test void controlLoginSignsSevenDaysAndReturnsSameLifetimeWithoutWeakeningMfaOrRevocation(){
  Fixture f=new Fixture();JwtUtil jwt=new JwtUtil();org.springframework.test.util.ReflectionTestUtils.setField(jwt,"secret","isolated-seven-day-login-test-key-not-used-in-production");org.springframework.test.util.ReflectionTestUtils.setField(jwt,"expireSeconds",30L);
  org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwords=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
  ControlMfa mfa=new ControlMfa(Base64.getEncoder().encodeToString(new byte[32]));String secret="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",code=ControlMfa.code(secret,System.currentTimeMillis()/30000);
  f.actor.setPasswordHash(passwords.encode("123456"));f.actor.setMfaEnabled(true);f.actor.setMfaSecret(mfa.encrypt(secret));when(f.admins.findByAccount("operator")).thenReturn(Optional.of(f.actor));
  ControlService service=new ControlService(f.admins,f.sessions,f.tenants,f.audit,jwt,passwords,mfa,mock(TenantHostService.class),true);
  assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.login("operator","wrong-password",code));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.login("operator","123456","invalid"));verifyNoInteractions(f.audit);
  long before=System.currentTimeMillis();Map<String,Object> result=service.login(" OPERATOR ","123456",code);long after=System.currentTimeMillis(),sevenDays=604800000L,expires=((Number)result.get("expiresAt")).longValue();
  io.jsonwebtoken.Claims claims=jwt.parse((String)result.get("token"));assertEquals(sevenDays,claims.getExpiration().getTime()-claims.getIssuedAt().getTime());assertTrue(expires>=before+sevenDays&&expires<=after+sevenDays);assertTrue(expires>=claims.getExpiration().getTime()&&expires-claims.getExpiration().getTime()<after-before+1000);
  assertEquals("control-9",claims.getSubject());assertEquals("control",claims.get("userType"));assertNull(claims.get("tenantId"));assertEquals(30L,jwt.getExpireSeconds());assertEquals(f.actor,service.validateControl(claims));
  verify(f.audit).record(eq(9L),isNull(),isNull(),eq("CONTROL_LOGIN"),eq("9"),eq("SUCCESS"),eq(""),isNull());
  when(f.admins.lock(9L)).thenReturn(Optional.of(f.actor));service.logout();assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.validateControl(claims));
  f.actor.setSessionVersion(0);f.actor.setEnabled(false);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.validateControl(claims));f.actor.setEnabled(true);f.actor.setMfaEnabled(false);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.login("operator","123456",code));
 }
 @Test void oneTimeTicketBindsTenantBrowserAndRealActor(){
  Fixture f=new Fixture();String binding="abcdefghijklmnopqrstuvwxyz0123456789";
  Map<String,Object> ticket=f.service.ticket(2L,binding);ControlAccessSession s=f.saved[0];assertNotEquals(ticket.get("ticket"),s.getTicketHash());
  assertEquals(60,java.time.Duration.between(s.getLastActivityAt(),s.getTicketExpiresAt()).getSeconds());assertEquals(3600,java.time.Duration.between(s.getLastActivityAt(),s.getExpiresAt()).getSeconds());
  when(f.sessions.lockTicket(s.getTicketHash())).thenReturn(Optional.of(s));
  assertThrows(RuntimeException.class,()->f.service.exchange((String)ticket.get("ticket"),binding,3L));assertFalse(s.isConsumed());
  assertThrows(RuntimeException.class,()->f.service.exchange((String)ticket.get("ticket"),"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",2L));assertFalse(s.isConsumed());
  Map<String,Object> result=f.service.exchange((String)ticket.get("ticket"),binding,2L);assertTrue(s.isConsumed());assertEquals("control",((Map<?,?>)result.get("user")).get("userType"));assertEquals(-9L,((Map<?,?>)result.get("user")).get("id"));
  assertThrows(RuntimeException.class,()->f.service.exchange((String)ticket.get("ticket"),binding,2L));
 }
 @Test void accessRevocationVersionAndIdleDoNotDependOnJwtExpiry(){
  Fixture f=new Fixture();f.service.ticket(2L,"abcdefghijklmnopqrstuvwxyz0123456789");ControlAccessSession s=f.saved[0];s.setConsumed(true);when(f.sessions.lock(s.getId())).thenReturn(Optional.of(s));
  DefaultClaims claims=new DefaultClaims();claims.setSubject("control_access-9");claims.put("accessSessionId",s.getId());claims.put("tenantId",2L);
  assertEquals(2L,f.service.validateAccess(claims).getTenantId());LocalDateTime activity=s.getLastActivityAt();f.service.validateAccess(claims);assertEquals(activity,s.getLastActivityAt(),"polling must not reset idle timeout");
  claims.put("tenantId",3L);assertThrows(RuntimeException.class,()->f.service.validateAccess(claims));claims.put("tenantId",2L);
  s.setRevoked(true);assertThrows(RuntimeException.class,()->f.service.validateAccess(claims));s.setRevoked(false);
  s.setLastActivityAt(LocalDateTime.now().minusMinutes(16));assertThrows(RuntimeException.class,()->f.service.validateAccess(claims));s.setLastActivityAt(LocalDateTime.now());
  f.actor.setSessionVersion(1);assertThrows(RuntimeException.class,()->f.service.validateAccess(claims));
 }
 @Test void accountRegistryNormalizationDoesNotUseEmailFallback(){assertEquals("agent@example.com",BackendLoginRegistry.normalize(" Agent@Example.com "));assertThrows(RuntimeException.class,()->BackendLoginRegistry.normalize("space name"));}
 @Test void auditKeepsServerChangeSummaryWhileRedactingCredentials(){
  ControlAuditLogRepository logs=mock(ControlAuditLogRepository.class);ControlAuditService service=new ControlAuditService(logs);service.record(9L,2L,"session","UPDATE","item","SUCCESS","status:ACTIVE->STOP_NEW","maintenance");
  org.mockito.ArgumentCaptor<ControlAuditLog> capture=org.mockito.ArgumentCaptor.forClass(ControlAuditLog.class);verify(logs).save(capture.capture());assertTrue(capture.getValue().getDetail().contains("ACTIVE->STOP_NEW"));
  String redacted=com.gtcfesk.exchange.common.LogRedaction.sanitize("{\"totp\":\"123456\",\"ticket\":\"do-not-log\",\"browserBinding\":\"do-not-log\",\"amount\":12}");assertFalse(redacted.contains("123456"));assertFalse(redacted.contains("do-not-log"));assertTrue(redacted.contains("12"));
 }
 @Test void alternateFrontendOriginRequiresExactOperatorLocalOptIn(){
  TenantHostService hosts=new TenantHostService(mock(TenantRepository.class),"localhost","https://admin.localhost:8443","https://control.localhost:8443","");
  com.gtcfesk.exchange.security.OutboundEndpointPolicy routing=new com.gtcfesk.exchange.security.OutboundEndpointPolicy();
  org.springframework.test.util.ReflectionTestUtils.setField(hosts,"routing",routing);
  org.springframework.test.util.ReflectionTestUtils.setField(routing,"localRoutingPort",8443);
  org.springframework.test.util.ReflectionTestUtils.setField(routing,"localLoopbackEndpoints","a.localhost:8443");
  assertEquals("https://a.localhost",hosts.frontendOrigin("a.localhost"));
  org.springframework.test.util.ReflectionTestUtils.setField(routing,"localLoopbackEnabled",true);
  assertEquals("https://a.localhost:8443",hosts.frontendOrigin("a.localhost"));
  assertEquals("https://b.localhost",hosts.frontendOrigin("b.localhost"));
  assertEquals("https://public.example.com",hosts.frontendOrigin("public.example.com"));
 }
 static class Fixture {
  ControlAdminRepository admins=mock(ControlAdminRepository.class);ControlAccessSessionRepository sessions=mock(ControlAccessSessionRepository.class);TenantRepository tenants=mock(TenantRepository.class);ControlAuditService audit=mock(ControlAuditService.class);JwtUtil jwt=mock(JwtUtil.class);ControlAdmin actor=new ControlAdmin();ControlAccessSession[] saved=new ControlAccessSession[1];ControlService service;
  Fixture(){actor.setId(9L);actor.setAccount("operator");Tenant tenant=new Tenant();tenant.setId(2L);tenant.setName("A");when(admins.findById(9L)).thenReturn(Optional.of(actor));when(tenants.findById(2L)).thenReturn(Optional.of(tenant));when(sessions.saveAndFlush(any())).thenAnswer(invocation->{saved[0]=invocation.getArgument(0);return saved[0];});TenantHostService hosts=mock(TenantHostService.class);when(hosts.adminOrigin()).thenReturn("https://admin.example.com");service=new ControlService(admins,sessions,tenants,audit,jwt,mock(PasswordEncoder.class),mock(ControlMfa.class),hosts,false);UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("9",null,Collections.emptyList());auth.setDetails(new ControlIdentity(9L,null,null));SecurityContextHolder.getContext().setAuthentication(auth);}
 }
}
