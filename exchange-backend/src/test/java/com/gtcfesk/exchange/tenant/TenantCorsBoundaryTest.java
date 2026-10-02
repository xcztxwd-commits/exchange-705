package com.gtcfesk.exchange.tenant;

import com.gtcfesk.exchange.config.CorsConfig;
import com.gtcfesk.exchange.control.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantCorsBoundaryTest {
 private final com.gtcfesk.exchange.control.TenantRepository tenants=mock(com.gtcfesk.exchange.control.TenantRepository.class);
 private final TenantHostService hosts=new TenantHostService(tenants,"example.test","https://admin.example.test","https://control.example.test","");
 private boolean request(String host,String origin,String forwarded)throws Exception{
  MockHttpServletRequest r=new MockHttpServletRequest("POST","/api/auth/login");r.setScheme("http");r.setServerName(host);r.setServerPort(8080);r.setRemoteAddr("127.0.0.1");r.addHeader("Host",host);r.addHeader("Origin",origin);
  if(forwarded!=null)r.addHeader("X-Forwarded-Host",forwarded);
  MockHttpServletResponse response=new MockHttpServletResponse();boolean[] reached={false};new CorsConfig().corsFilter(hosts).doFilter(r,response,(a,b)->reached[0]=true);return reached[0];
 }
 @Test void tlsTerminatingGatewayAcceptsExactPublicOrigin()throws Exception{assertTrue(request("control.example.test","https://control.example.test",null));assertTrue(request("admin.example.test","https://admin.example.test",null));}
 @Test void arbitraryAndUntrustedForwardedOriginsRejected()throws Exception{assertFalse(request("control.example.test","https://attacker.example.test",null));assertFalse(request("admin.example.test","https://admin.example.test","control.example.test"));}
 @Test void tenantOriginRequiresCurrentVerifiedBinding()throws Exception{
  Tenant tenant=new Tenant();tenant.setId(2L);tenant.setFrontendHost("a.example.test");tenant.setDomainVerified(true);tenant.setStatus("ACTIVE");when(tenants.findByFrontendHost("a.example.test")).thenReturn(Optional.of(tenant));
  assertTrue(request("a.example.test","https://a.example.test",null));assertFalse(request("a.example.test","https://b.example.test",null));tenant.setDomainVerified(false);assertFalse(request("a.example.test","https://a.example.test",null));
 }
}
