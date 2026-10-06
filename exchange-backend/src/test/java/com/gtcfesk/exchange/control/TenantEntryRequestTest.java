package com.gtcfesk.exchange.control;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TenantEntryRequestTest {
 TenantRepository tenants;TenantDomainVerification domains;TenantHostService hosts;TenantRequestFilter filter;Tenant frontend;int passed;
 @BeforeEach void setup(){tenants=mock(TenantRepository.class);domains=mock(TenantDomainVerification.class);hosts=new TenantHostService(tenants,"forex-exchange.cc","https://admin-panel.forex-exchange.cc","https://control.forex-exchange.cc","127.0.0.1/32");filter=new TenantRequestFilter(hosts);ReflectionTestUtils.setField(filter,"domains",domains);frontend=new Tenant();frontend.setId(17L);frontend.setFrontendHost("different.forex-exchange.cc");frontend.setDomainVerified(true);frontend.setStatus("ACTIVE");when(tenants.findByFrontendHost(anyString())).thenReturn(Optional.empty());when(tenants.findByFrontendHost(frontend.getFrontendHost())).thenReturn(Optional.of(frontend));when(domains.entryTarget("entry.forex-exchange.net")).thenReturn(frontend.getFrontendHost());when(domains.entryTarget("unknown.forex-exchange.net")).thenThrow(new org.springframework.security.access.AccessDeniedException("unavailable"));}
 @AfterEach void cleanup(){assertNull(TenantContext.currentTenantId());TenantContext.clear();}
 MockHttpServletRequest request(String host,String method,String path){MockHttpServletRequest r=new MockHttpServletRequest(method,path);r.setRemoteAddr("127.0.0.1");r.addHeader("Host",host);r.addHeader("Accept","text/html");r.addHeader("Sec-Fetch-Mode","navigate");r.addHeader("Sec-Fetch-Dest","document");return r;}
 MockHttpServletResponse call(MockHttpServletRequest r)throws Exception{MockHttpServletResponse out=new MockHttpServletResponse();filter.doFilter(r,out,(req,res)->{passed++;assertEquals(17L,TenantContext.requireTenantId());res.getWriter().write("FRONTEND");});return out;}
 void rejected(MockHttpServletRequest r)throws Exception{int before=passed;MockHttpServletResponse out=call(r);assertEquals(403,out.getStatus());assertEquals("no-store",out.getHeader("Cache-Control"));assertNull(out.getHeader("Location"));assertNull(out.getHeader("Access-Control-Allow-Origin"));assertEquals(before,passed);assertFalse(out.getContentAsString().contains("different.forex"));}
 @Test void onlyAllowedNavigationRedirectsToExplicitSameTenantHostWithNoCacheOrCredentials()throws Exception{
  for(String method:Arrays.asList("GET","HEAD")){MockHttpServletRequest r=request("entry.forex-exchange.net",method,"/mobile/trade");r.setQueryString("symbol=EURUSD&tab=term&access_token=secret&code=oauth&state=secret");r.addParameter("symbol","EURUSD");r.addParameter("tab","term");r.addParameter("access_token","secret");r.addParameter("code","oauth");r.addParameter("state","secret");r.addHeader("Authorization","Bearer ignored-entry-token");MockHttpServletResponse out=call(r);assertEquals(302,out.getStatus());String location=out.getHeader("Location");assertTrue(location.startsWith("https://different.forex-exchange.cc/mobile/trade?"));assertTrue(location.contains("symbol=EURUSD"));assertTrue(location.contains("tab=term"));assertFalse(location.contains("secret"));assertFalse(location.contains("code="));assertEquals("no-store",out.getHeader("Cache-Control"));assertEquals("no-referrer",out.getHeader("Referrer-Policy"));assertNull(out.getHeader("Access-Control-Allow-Origin"));}
  assertEquals(0,passed);
 }
 @Test void disabledAndUnknownEntryAreIndistinguishableAndFrontendDirectAccessStillWorks()throws Exception{
  when(domains.entryTarget("entry.forex-exchange.net")).thenThrow(new org.springframework.security.access.AccessDeniedException("closed"));MockHttpServletResponse closed=call(request("entry.forex-exchange.net","GET","/")),unknown=call(request("unknown.forex-exchange.net","GET","/"));assertEquals(403,closed.getStatus());assertEquals(closed.getContentAsString(),unknown.getContentAsString());assertNull(closed.getHeader("Location"));assertEquals("no-store",closed.getHeader("Cache-Control"));
  MockHttpServletResponse direct=call(request(frontend.getFrontendHost(),"GET","/api/user/profile"));assertEquals("FRONTEND",direct.getContentAsString());assertEquals(1,passed);
 }
 @Test void entryNeverProvidesApisUploadsWebsocketsAdminLoginOrCallback()throws Exception{
  for(String path:Arrays.asList("/api/auth/login","/api/market/ticker","/api/admin/auth/login","/api/control/auth/login","/api/upload/image","/uploads/a.png","/api/ws/market","/payment/callback","/demo-api/auth/login","/healthz","/assets/index.js","/entry.html","/domain-transition.html"))for(String method:Arrays.asList("GET","POST","OPTIONS"))rejected(request("entry.forex-exchange.net",method,path));
  MockHttpServletRequest upgrade=request("entry.forex-exchange.net","GET","/");upgrade.addHeader("Upgrade","websocket");rejected(upgrade);
  MockHttpServletRequest fetch=request("entry.forex-exchange.net","GET","/");fetch.removeHeader("Sec-Fetch-Mode");fetch.addHeader("Sec-Fetch-Mode","cors");rejected(fetch);
 }
 @Test void openRedirectEncodedPathsAndForgedHostsCannotReachAnyTenant()throws Exception{
  for(String path:Arrays.asList("//evil.example/","/mobile//evil.example/","/%2f%2fevil","/mobile/../api/auth/login","/mobile/%2e%2e/api/auth/login","/mobile/trade;api","/mobile/trade%0d%0aLocation:evil"))rejected(request("entry.forex-exchange.net","GET",path));
  MockHttpServletRequest redirect=request("entry.forex-exchange.net","GET","/");redirect.addParameter("next","https://evil.example");rejected(redirect);
  for(String host:Arrays.asList("unknown.forex-exchange.cc","entry.forex-exchange.net@evil.example","entry.forex-exchange.net,evil.example","entry.forex-exchange.net/path"))rejected(request(host,"GET","/"));
  MockHttpServletRequest forged=request("entry.forex-exchange.net","GET","/");forged.addHeader("X-Forwarded-Host",frontend.getFrontendHost());rejected(forged);
  MockHttpServletRequest duplicate=request("entry.forex-exchange.net","GET","/");duplicate.addHeader("Host",frontend.getFrontendHost());rejected(duplicate);
  MockHttpServletRequest sni=request(frontend.getFrontendHost(),"GET","/");sni.addHeader("X-Edge-TLS-Server-Name","entry.forex-exchange.net");rejected(sni);
  MockHttpServletRequest duplicateSni=request(frontend.getFrontendHost(),"GET","/");duplicateSni.addHeader("X-Edge-TLS-Server-Name",frontend.getFrontendHost());duplicateSni.addHeader("X-Edge-TLS-Server-Name","entry.forex-exchange.net");rejected(duplicateSni);
  MockHttpServletRequest external=request("entry.forex-exchange.net","GET","/");external.setRemoteAddr("8.8.8.8");external.addHeader("X-Forwarded-Host","entry.forex-exchange.net");rejected(external);
 }
 @Test void challengeIsDirectNonceBoundJsonAndNeverRedirects()throws Exception{
  String nonce="a".repeat(32);Map<String,Object> reply=Map.of("tenantId",17L,"role","ENTRY","challenge",nonce);when(domains.routing("entry.forex-exchange.net",nonce)).thenReturn(reply);
  MockHttpServletRequest r=request("entry.forex-exchange.net","GET","/api/tenant-routing-check");r.addParameter("challenge",nonce);MockHttpServletResponse out=call(r);assertEquals(200,out.getStatus());assertTrue(out.getContentAsString().contains(nonce));assertEquals("no-store",out.getHeader("Cache-Control"));assertNull(out.getHeader("Location"));verify(domains,never()).entryTarget(anyString());
  MockHttpServletRequest post=request("entry.forex-exchange.net","POST","/api/tenant-routing-check");post.addParameter("challenge",nonce);rejected(post);rejected(request("entry.forex-exchange.net","GET","/api/tenant-routing-check"));
 }
 @Test void gatewayChecksBeforeSpaAndCannotBeForgedByPublicClients()throws Exception{
  MockHttpServletRequest entry=request("entry.forex-exchange.net","GET","/internal/tenant-gateway");entry.addHeader("X-Tenant-Gateway","1");MockHttpServletResponse out=call(entry);assertEquals(401,out.getStatus());assertNull(out.getHeader("Location"));assertEquals("no-store",out.getHeader("Cache-Control"));
  MockHttpServletRequest direct=request(frontend.getFrontendHost(),"GET","/internal/tenant-gateway");direct.addHeader("X-Tenant-Gateway","1");assertEquals(204,call(direct).getStatus());
  MockHttpServletRequest untrusted=request(frontend.getFrontendHost(),"GET","/internal/tenant-gateway");untrusted.addHeader("X-Tenant-Gateway","1");untrusted.setRemoteAddr("8.8.8.8");rejected(untrusted);assertEquals(0,passed);
 }
 @Test void rolesRejectExternalRootsReservedLabelsPortsAndCrossRoleNames(){
  assertEquals("one.forex-exchange.net",hosts.validateAssignment("one.forex-exchange.net","ENTRY"));assertEquals("other.forex-exchange.cc",hosts.validateAssignment("other.forex-exchange.cc","FRONTEND"));
  for(String host:Arrays.asList("a.forex-exchange.cc","www.forex-exchange.net","admin.forex-exchange.net","control.forex-exchange.net","a.b.forex-exchange.net","forex-exchange.net","a.evil.example","a.forex-exchange.net:443"))assertThrows(RuntimeException.class,()->hosts.validateAssignment(host,"ENTRY"));assertThrows(RuntimeException.class,()->hosts.validateAssignment("a.forex-exchange.net","FRONTEND"));
 }
}
