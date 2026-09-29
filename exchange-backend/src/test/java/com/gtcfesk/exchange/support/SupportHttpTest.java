package com.gtcfesk.exchange.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.config.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.security.web.FilterChainProxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real JWT filter, security chain, permission interceptor and controllers against an isolated database. */
@SpringJUnitConfig({SupportServiceTest.Config.class, SupportHttpTest.Web.class})
@WebAppConfiguration
@TestPropertySource(properties={"jwt.secret=support-test-key-never-use-in-production-2026", "jwt.expireSeconds=3600", "security.trusted-proxies=10.0.0.0/8"})
class SupportHttpTest {
    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, JwtFilter.class, JwtUtil.class, BackendAccess.class, GlobalExceptionHandler.class,
        UserSupportController.class, AdminSupportController.class, SystemConfigController.class})
    static class Web implements WebMvcConfigurer {
        @Autowired BackendAccess access;
        @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(access).addPathPatterns("/api/admin/**"); }
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor mockMarketRegistration() {
            return factory -> factory.registerSingleton("market", org.mockito.Mockito.mock(com.gtcfesk.exchange.market.ForexQuoteMarketService.class));
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired JwtUtil jwt;
    @Autowired SupportSettings settings;
    @Autowired SupportPermissionCatalog catalog;
    @Autowired AdminUserRepository admins;
    @Autowired UserAccountRepository users;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminMenuRepository menus;
    @Autowired AdminRoleMenuRepository grants;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;
    String userToken, secondToken, adminToken, superToken, limitedToken, agentToken;
    Long userId;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build(); catalog.run();
        SupportSettings.Settings s=new SupportSettings.Settings();s.mode="internal";s.inboxEnabled=true;settings.save(s);
        UserAccount user=user("normal");userId=user.getId();userToken=token(user.getId(),"user",user.getCurrentToken(),user.getPasswordHash());
        UserAccount second=user("normal");secondToken=token(second.getId(),"user",second.getCurrentToken(),second.getPasswordHash());
        UserAccount agent=user("agent");agentToken=token(agent.getId(),"agent",agent.getCurrentToken(),agent.getPasswordHash());
        String role="http_"+UUID.randomUUID().toString().substring(0,12);AdminRole r=new AdminRole();r.setRoleCode(role);r.setRoleName(role);roles.saveAndFlush(r);
        for(AdminMenu m:menus.findAll())if(!"directory".equals(m.getMenuType())){AdminRoleMenu g=new AdminRoleMenu();g.setRoleId(r.getId());g.setMenuId(m.getId());grants.saveAndFlush(g);}
        adminToken=admin(role);superToken=admin("super_admin");limitedToken=admin("no_support_grants");
    }
    UserAccount user(String type){UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@test.invalid");u.setPasswordHash("unused");u.setUserType(type);u.setCurrentToken(UUID.randomUUID().toString());return users.saveAndFlush(u);}
    String admin(String role){AdminUser a=new AdminUser();a.setAccount(UUID.randomUUID().toString());a.setEmail(a.getAccount()+"@test.invalid");a.setPasswordHash("unused");a.setCurrentToken(UUID.randomUUID().toString());a.setRole(role);admins.saveAndFlush(a);return token(a.getId(),"admin",a.getCurrentToken(),a.getPasswordHash());}
    String token(Long id,String type,String sid,String password){Map<String,Object> c=new HashMap<>();c.put("userType",type);c.put("sid",sid);c.put("credential",jwt.credentialKey(password));return jwt.generateToken(type+"-"+id,c);}
    String json(Object o)throws Exception{return mapper.writeValueAsString(o);}
    long start()throws Exception{return mapper.readTree(mvc.perform(post("/api/user/support/sessions").header("Authorization","Bearer "+userToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();}
    @Test void authRoleAndMenuBoundaries()throws Exception{
        mvc.perform(get("/api/user/support/config")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("internal"));
        mvc.perform(get("/api/user/support/tones/arrival.wav")).andExpect(status().isOk()).andExpect(content().contentType("audio/wav"));
        mvc.perform(post("/api/user/support/sessions")).andExpect(status().isUnauthorized());
        for(String tk:Arrays.asList(userToken,agentToken,limitedToken))mvc.perform(get("/api/admin/support/sessions").header("Authorization","Bearer "+tk)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/support/sessions").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/support/sessions?scope=all").header("Authorization","Bearer "+adminToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/support/sessions?scope=all").header("Authorization","Bearer "+superToken)).andExpect(status().isOk());
        mvc.perform(get("/api/user/support/inbox").header("Authorization","Bearer "+adminToken)).andExpect(status().isForbidden());
    }
    @Test void liveQueueTextImageReadExportAndCrossUserDenial()throws Exception{
        long id=start();
        mvc.perform(get("/api/user/support/sessions/"+id).header("Authorization","Bearer "+secondToken)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/support/presence").header("Authorization","Bearer "+adminToken).contentType("application/json").content("{\"accepting\":true}")).andExpect(status().isOk());
        mvc.perform(post("/api/admin/support/sessions/"+id+"/claim").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk());
        String body="{\"requestId\":\""+UUID.randomUUID()+"\",\"text\":\"hello\"}";
        mvc.perform(post("/api/admin/support/sessions/"+id+"/messages").header("Authorization","Bearer "+adminToken).contentType("application/json").content(body)).andExpect(status().isOk());
        String result=mvc.perform(multipart("/api/admin/support/sessions/"+id+"/images").file(SupportServiceTest.png()).param("requestId",UUID.randomUUID().toString()).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long imageId=mapper.readTree(result).get("id").asLong();
        mvc.perform(get("/api/user/support/images/"+imageId).header("Authorization","Bearer "+userToken)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/user/support/images/"+imageId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/user/support/images/"+imageId).header("Authorization","Bearer "+secondToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/support/sessions/"+id+"/export").header("Authorization","Bearer "+adminToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/support/sessions/"+id+"/export").header("Authorization","Bearer "+superToken)).andExpect(status().isOk()).andExpect(jsonPath("$.chainValid").value(true));
    }
    @Test void noSettingsBypassAndDisabledRoutesRejectWrites()throws Exception{
        mvc.perform(post("/api/admin/support/settings").header("Authorization","Bearer "+limitedToken).contentType("application/json").content("{\"mode\":\"off\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/config/save").header("Authorization","Bearer "+limitedToken).contentType("application/json").content("{\"key\":\"support.settings\",\"value\":\"{}\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/support/settings").header("Authorization","Bearer "+superToken).contentType("application/json").content("{\"mode\":\"off\",\"inboxEnabled\":false}")).andExpect(status().isOk());
        mvc.perform(post("/api/user/support/sessions").header("Authorization","Bearer "+userToken)).andExpect(status().isConflict());
        mvc.perform(get("/api/user/support/inbox").header("Authorization","Bearer "+userToken)).andExpect(status().isConflict());
    }

    @Test void forwardedIpIsAcceptedOnlyFromConfiguredProxy() throws Exception {
        mvc.perform(post("/api/user/support/sessions").header("Authorization", "Bearer "+userToken)
            .header("X-Real-IP", "192.0.2.8").with(r -> { r.setRemoteAddr("198.51.100.4"); return r; }))
            .andExpect(status().isOk()).andExpect(jsonPath("$.clientIp").value("198.51.100.4"));
        mvc.perform(post("/api/user/support/sessions").header("Authorization", "Bearer "+secondToken)
            .header("X-Real-IP", "192.0.2.8").with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }))
            .andExpect(status().isOk()).andExpect(jsonPath("$.clientIp").value("192.0.2.8"));
    }
}
