package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.simulation.AccountInspection;
import com.gtcfesk.exchange.user.UserActivityService;
import com.gtcfesk.exchange.tenant.TenantContext;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
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

/** Real Host/JWT/Spring Security/MVC routing; reuse the existing local-only boundary fixture. */
@SpringJUnitConfig(ControlChatAllHttpTest.Config.class) @WebAppConfiguration
@TestPropertySource(properties={"jwt.secret=chat-all-test-only-not-a-production-key","jwt.expireSeconds=60","platform.base-domain=example.test","platform.admin-origin=https://admin.example.test","platform.control-origin=https://control.example.test"})
class ControlChatAllHttpTest {
 @Configuration @Import({ControlExchangeHttpTest.Config.class,ControlReadController.class})
 static class Config {
  @Bean UserActivityService activity(){return mock(UserActivityService.class);}
  @Bean AccountInspection inspection(){return mock(AccountInspection.class);}
  @Bean JdbcTemplate jdbc(){return mock(JdbcTemplate.class);}
  @Bean ControlReadQueryService queries(){return mock(ControlReadQueryService.class);}
 }
 @Autowired WebApplicationContext context;@Autowired @Qualifier("springSecurityFilterChain") Filter security;
 @Autowired TenantRequestFilter hostBoundary;@Autowired ControlService service;@Autowired JwtUtil jwt;
 @Autowired ControlReadQueryService queries;@Autowired ControlAuditService audit;@Autowired TenantRepository tenants;
 MockMvc mvc;
 @BeforeEach void setup(){reset(service,jwt,queries,audit,tenants);mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(hostBoundary,security).build();claims("control");ControlAdmin actor=new ControlAdmin();actor.setId(9L);when(service.validateControl(any())).thenReturn(actor);}
 @AfterEach void cleanup(){TenantContext.clear();SecurityContextHolder.clearContext();}
 void claims(String type){DefaultClaims c=new DefaultClaims();c.setSubject(type+"-9");c.setExpiration(new Date(System.currentTimeMillis()+60000));c.put("userType",type);c.put("tenantId",2L);when(jwt.parse("test")).thenReturn(c);}
 @Test void globalGetUsesBoundedProjectionAndRecordsExplicitAllTenantReadBeforeResponse()throws Exception{
  when(queries.allConversations(7L,8L,"person@example.test","2026-01-01T00:00:00Z","2026-01-02T00:00:00Z",1)).thenReturn(Arrays.asList(Map.of("id",4,"tenant_id",2,"tenant_name","A"),Map.of("id",3,"tenant_id",3,"tenant_name","B")));
  mvc.perform(get("/api/control/support/conversations").header("Host","control.example.test").header("Authorization","Bearer test").param("userId","7").param("adminId","8").param("userEmail","person@example.test").param("createdFrom","2026-01-01T00:00:00Z").param("createdTo","2026-01-02T00:00:00Z").param("page","1").param("tenantId","999"))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-cache, no-store, max-age=0, must-revalidate"))
   .andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].tenant_id").value(2)).andExpect(jsonPath("$.data[1].tenant_id").value(3));
  verify(queries).allConversations(7L,8L,"person@example.test","2026-01-01T00:00:00Z","2026-01-02T00:00:00Z",1);
  verify(audit).record(eq(9L),isNull(),isNull(),eq("CHAT_SUPERVISE_ALL"),contains("scope=all;page=1"),eq("SUCCESS"),eq("read-only;scope=all"),isNull());verifyNoInteractions(tenants);assertNull(TenantContext.currentTenantId());
 }
 @Test void anonymousForeignHostsAndTenantCredentialsCannotUseGlobalList()throws Exception{
  mvc.perform(get("/api/control/support/conversations").header("Host","control.example.test")).andExpect(status().isUnauthorized());
  for(String host:new String[]{"admin.example.test","evil.example.test"})mvc.perform(get("/api/control/support/conversations").header("Host",host).header("Authorization","Bearer test")).andExpect(status().isForbidden());
  mvc.perform(get("/api/control/support/conversations").header("Host","control.example.test").header("Origin","https://evil.example.test").header("Authorization","Bearer test")).andExpect(status().isForbidden());
  for(String type:new String[]{"admin","agent","user","control_access"}){claims(type);mvc.perform(get("/api/control/support/conversations").header("Host","control.example.test").header("Authorization","Bearer test")).andExpect(status().isUnauthorized());}
  verifyNoInteractions(queries);verify(service,never()).validateAccess(any());
 }
 @Test void existingSingleTenantGetKeepsItsScopeAndAudit()throws Exception{
  Tenant t=new Tenant();t.setId(2L);when(tenants.findById(2L)).thenReturn(Optional.of(t));when(queries.conversations(null,null,null,null,null,0)).thenAnswer(call->{assertEquals(2L,TenantContext.requireTenantId());return Collections.singletonList(Map.of("id",4,"tenant_id",2));});
  mvc.perform(get("/api/control/tenants/2/support/conversations").header("Host","control.example.test").header("Authorization","Bearer test")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].tenant_id").value(2));
  verify(queries,never()).allConversations(any(),any(),any(),any(),any(),anyInt());verify(audit).record(eq(9L),eq(2L),isNull(),eq("CHAT_SUPERVISE"),anyString(),eq("SUCCESS"),eq("read-only"),isNull());assertNull(TenantContext.currentTenantId());
 }
 @Test void globalGetFailsClosedWhenAuditCannotBeSaved()throws Exception{
  when(queries.allConversations(null,null,null,null,null,0)).thenReturn(Collections.emptyList());doThrow(new IllegalStateException("local test audit unavailable")).when(audit).record(any(),any(),any(),any(),any(),any(),any(),any());
  mvc.perform(get("/api/control/support/conversations").header("Host","control.example.test").header("Authorization","Bearer test")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.success").value(false));
  verify(audit).failure(eq(9L),isNull(),isNull(),eq("CHAT_SUPERVISE_ALL"),contains("scope=all"));
 }
}
