package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.simulation.AccountInspection;
import com.gtcfesk.exchange.tenant.TenantContext;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.servlet.Filter;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Existing real Host/JWT/security chain, plus every new platform-only read route and original scoped route. */
@SpringJUnitConfig(ControlAllTenantsHttpTest.Config.class) @WebAppConfiguration
@TestPropertySource(properties={"jwt.secret=control-all-test-only-not-a-production-key","jwt.expireSeconds=60","platform.base-domain=example.test","platform.admin-origin=https://admin.example.test","platform.control-origin=https://control.example.test"})
class ControlAllTenantsHttpTest {
 @Configuration @Import({ControlChatAllHttpTest.Config.class,OperationalIssueController.class}) static class Config {
  @Bean OperationalIssueService issues(){return mock(OperationalIssueService.class);}
 }
 @Autowired WebApplicationContext context;@Autowired @Qualifier("springSecurityFilterChain") Filter security;
 @Autowired TenantRequestFilter hostBoundary;@Autowired ControlService service;@Autowired JwtUtil jwt;
 @Autowired ControlReadQueryService queries;@Autowired AccountInspection inspection;@Autowired OperationalIssueService issues;@Autowired ControlAuditService audit;@Autowired TenantRepository tenants;
 MockMvc mvc;
 final List<String> routes=Arrays.asList("/business/users","/supervision/kyc","/supervision/admins","/supervision/agents","/supervision/statistics","/operations/issues");
 @BeforeEach void setup(){reset(service,jwt,queries,inspection,issues,audit,tenants);mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(hostBoundary,security).build();claims("control");ControlAdmin actor=new ControlAdmin();actor.setId(9L);when(service.validateControl(any())).thenReturn(actor);when(issues.actor()).thenAnswer(call->ControlIdentity.requireIndependent());}
 @AfterEach void cleanup(){TenantContext.clear();SecurityContextHolder.clearContext();}
 void claims(String type){DefaultClaims c=new DefaultClaims();c.setSubject(type+"-9");c.setExpiration(new Date(System.currentTimeMillis()+60000));c.put("userType",type);c.put("tenantId",2L);when(jwt.parse("test")).thenReturn(c);}
 @Test void everyAllRouteUsesExplicitProjectionAndAuditsPlatformScope()throws Exception{
  when(inspection.readAll("users",7L,"example.test","PENDING",2,20)).thenReturn(Map.of("rows",Arrays.asList(Map.of("tenant_id",2),Map.of("tenant_id",3)),"total",25));
  mvc.perform(get("/api/control/business/users").header("Host","control.example.test").header("Authorization","Bearer test").param("userId","7").param("userEmail","example.test").param("status","PENDING").param("page","2").param("tenantId","999"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(25)).andExpect(jsonPath("$.data.rows[1].tenant_id").value(3));
  verify(inspection).readAll("users",7L,"example.test","PENDING",2,20);verify(audit).record(eq(9L),isNull(),isNull(),eq("BUSINESS_READ_ALL"),eq("users;scope=all;page=2"),eq("SUCCESS"),eq("read-only;scope=all"),isNull());
  for(String kind:Arrays.asList("kyc","admins","agents")){mvc.perform(get("/api/control/supervision/"+kind).header("Host","control.example.test").header("Authorization","Bearer test").param("subjectId","7").param("userEmail","example.test")).andExpect(status().isOk());verify(queries).allRecords(kind,7L,"example.test",null,1,20);verify(audit).record(eq(9L),isNull(),isNull(),eq("SUPERVISION_READ_ALL"),eq(kind+";scope=all;page=1"),eq("SUCCESS"),eq("read-only;scope=all"),isNull());}
  mvc.perform(get("/api/control/supervision/statistics").header("Host","control.example.test").header("Authorization","Bearer test")).andExpect(status().isOk());verify(queries).allStatistics();verify(audit).record(eq(9L),isNull(),isNull(),eq("STATISTICS_READ_ALL"),contains("scope=all"),eq("SUCCESS"),eq("read-only;scope=all"),isNull());
  mvc.perform(get("/api/control/operations/issues").header("Host","control.example.test").header("Authorization","Bearer test").param("page","1")).andExpect(status().isOk());verify(issues).listAll(1);verify(audit).record(eq(9L),isNull(),isNull(),eq("OPERATIONAL_ISSUES_READ_ALL"),contains("scope=all;page=1"),eq("SUCCESS"),eq("read-only;scope=all"),isNull());verifyNoInteractions(tenants);assertNull(TenantContext.currentTenantId());
 }
 @Test void allReadsFailClosedOnAuditFailureAndRemainGetOnly()throws Exception{
  doThrow(new IllegalStateException("test audit unavailable")).when(audit).record(any(),any(),any(),any(),any(),any(),any(),any());
  for(String path:routes){mvc.perform(get("/api/control"+path).header("Host","control.example.test").header("Authorization","Bearer test")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.success").value(false));mvc.perform(post("/api/control"+path).header("Host","control.example.test").header("Authorization","Bearer test").contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow","GET"));}
  verify(audit).failure(eq(9L),isNull(),isNull(),eq("BUSINESS_READ_ALL"),anyString());verify(audit).failure(eq(9L),isNull(),isNull(),eq("STATISTICS_READ_ALL"),anyString());verify(audit).failure(eq(9L),isNull(),isNull(),eq("OPERATIONAL_ISSUES_READ_ALL"),anyString());
 }
 @Test void everyAllRouteRejectsAnonymousForeignHostsAndTenantTokens()throws Exception{
  for(String path:routes){mvc.perform(get("/api/control"+path).header("Host","control.example.test")).andExpect(status().isUnauthorized());mvc.perform(get("/api/control"+path).header("Host","admin.example.test").header("Authorization","Bearer test")).andExpect(status().isForbidden());mvc.perform(get("/api/control"+path).header("Host","control.example.test").header("Origin","https://evil.example.test").header("Authorization","Bearer test")).andExpect(status().isForbidden());}
  for(String type:Arrays.asList("admin","user","agent","control_access")){claims(type);for(String path:routes)mvc.perform(get("/api/control"+path).header("Host","control.example.test").header("Authorization","Bearer test")).andExpect(status().isUnauthorized());}
  verifyNoInteractions(inspection,queries,issues);verify(service,never()).validateAccess(any());
 }
 @Test void originalSpecificTenantReadsAndReviewNeverBecomeGlobal()throws Exception{
  Tenant t=new Tenant();t.setId(2L);when(tenants.findById(2L)).thenReturn(Optional.of(t));
  when(inspection.read("users",null,null,null,1,20)).thenAnswer(call->{assertEquals(2L,TenantContext.requireTenantId());return Map.of("rows",Collections.emptyList());});
  mvc.perform(get("/api/control/tenants/2/business/users").header("Host","control.example.test").header("Authorization","Bearer test")).andExpect(status().isOk());
  for(String kind:Arrays.asList("kyc","admins","agents"))mvc.perform(get("/api/control/tenants/2/supervision/"+kind).header("Host","control.example.test").header("Authorization","Bearer test")).andExpect(status().isOk());
  mvc.perform(get("/api/control/tenants/2/supervision/statistics").header("Host","control.example.test").header("Authorization","Bearer test")).andExpect(status().isOk());mvc.perform(get("/api/control/operations/tenants/2").header("Host","control.example.test").header("Authorization","Bearer test")).andExpect(status().isOk());
  mvc.perform(post("/api/control/operations/tenants/2/review").header("Host","control.example.test").header("Authorization","Bearer test").contentType("application/json").content("{\"job\":\"contract-match\",\"expectedFailureId\":7,\"reason\":\"Verified scoped report\",\"evidenceSha256\":\""+"a".repeat(64)+"\",\"result\":\"PRESERVED\"}")).andExpect(status().isOk());verify(issues).review(2L,"contract-match",7,"Verified scoped report","a".repeat(64),"PRESERVED");
  verify(inspection,never()).readAll(anyString(),any(),any(),any(),anyInt(),anyInt());verify(queries,never()).allStatistics();verify(issues,never()).listAll(anyInt());assertNull(TenantContext.currentTenantId());
 }
}
