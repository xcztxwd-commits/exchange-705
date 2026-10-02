package com.gtcfesk.exchange.security;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.config.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.market.*;
import com.gtcfesk.exchange.service.EmailService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.mock.web.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real JWT filter, MVC/body guards, repositories and isolated H2; no business database changes. */
@org.springframework.context.annotation.Import(com.gtcfesk.exchange.tenant.BootTenantFixture.class)
@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
@org.springframework.test.context.TestPropertySource(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:multitenant-market-test.sql","spring.redis.host=127.0.0.1", "spring.redis.port=${MT705_TEST_REDIS_PORT:1}", "spring.redis.password=${MT705_TEST_REDIS_PASSWORD:}", "platform.base-domain=mt705.test","platform.admin-origin=https://admin.mt705.test","platform.control-origin=https://control.mt705.test"})
@SpringBootTest(classes = AdminPermissionIntegrationTest.PermissionApp.class, properties = {"spring.datasource.url=jdbc:h2:mem:admin_permission;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false", "logging.level.root=ERROR", "jwt.secret=cGVybWlzc2lvbi10ZXN0LW9ubHktbm90LWEtcmVhbC1zZWNyZXQ="})
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class AdminPermissionIntegrationTest {
    // Keep unrelated live-browser test servers out of this production-controller integration context.
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.test.context.TestComponent
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages="com.gtcfesk.exchange", repositoryFactoryBeanClass=com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean.class)
    @org.springframework.boot.autoconfigure.domain.EntityScan("com.gtcfesk.exchange")
    @org.springframework.context.annotation.ComponentScan(basePackages="com.gtcfesk.exchange", excludeFilters={
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.CUSTOM, classes=org.springframework.boot.context.TypeExcludeFilter.class),
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes=com.gtcfesk.exchange.ExchangeBackendApplication.class),
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.REGEX, pattern="com\\.gtcfesk\\.exchange\\.support\\.UnifiedInbox.*Fixture.*")
    })
    static class PermissionApp { }
    @MockBean ForexQuoteMarketService quotes;
    @MockBean MarketInstrumentCatalog marketCatalog;
    @MockBean MarketOrderProcessor processor;
    @MockBean RedisMarketService redis;
    @MockBean EmailService email;
    @MockBean RegistrationSecurity registration;

    @Autowired com.gtcfesk.exchange.control.BackendLoginRegistry backendNames;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AdminMenuRepository menus;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminRoleMenuRepository grants;
    @Autowired AdminUserRepository admins;
    @Autowired UserAccountRepository users;
    @Autowired UserMenuRepository userMenus;
    @Autowired UserActionRepository actions;
    @Autowired AdminPermissionService permissions;
    @Autowired AdminPermissionCatalog catalog;
    @Autowired BackendAccess guard;
    @Autowired JwtUtil jwt;
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired OptionDurationRepository durations;
    @Autowired com.gtcfesk.exchange.control.TenantPolicyRepository tenantPolicies;
    AdminRole role;
    AdminUser actor;
    String token, superToken;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        role = new AdminRole(); role.setRoleName("QA_" + UUID.randomUUID()); role.setRoleCode("qa_" + UUID.randomUUID().toString().substring(0,8)); role.setStatus("active"); role = roles.saveAndFlush(role);
        actor = admin(role.getRoleCode()); token = token(actor); superToken = token(admin("super_admin"));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    AdminUser admin(String code) {
        AdminUser a = new AdminUser(); a.setAccount("qa_" + UUID.randomUUID()); a.setEmail(a.getAccount()+"@example.invalid"); a.setRole(code); a.setEnabled(true); a.setPasswordHash("test-hash"); a.setCurrentToken(UUID.randomUUID().toString()); a=admins.saveAndFlush(a);backendNames.register("ADMIN",a.getId(),a.getAccount());return a;
    }
    String token(AdminUser a) {
        Map<String,Object> c = new HashMap<>(); c.put("userType","admin"); c.put("sid",a.getCurrentToken()); c.put("credential",jwt.credentialKey(a.getPasswordHash()));
        return jwt.generateToken("admin-"+a.getId(),c);
    }
    void authenticate() { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor.getId().toString(),null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")))); }
    AdminMenu menu(String code) { return menus.findByMenuCode(code).orElseThrow(AssertionError::new); }
    void grant(String code) { grant(menu(code).getId()); }
    void grant(Long id) { AdminRoleMenu g = new AdminRoleMenu(); g.setRoleId(role.getId()); g.setMenuId(id); grants.saveAndFlush(g); }
    int call(String method, String path, String body, String tk) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path).header("Authorization","Bearer "+tk);
        if (body != null) request.contentType("application/json").content(body);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }
    JsonNode getJson(String path, String tk) throws Exception { return json.readTree(mvc.perform(get(path).header("Authorization","Bearer "+tk)).andReturn().getResponse().getContentAsString()); }

    @Test void catalogIsIdempotentAndContainsTwoMenuLevels() throws Exception {
        long before=menus.count(); catalog.run(); assertEquals(before,menus.count());
        List<AdminMenu> all=menus.findAll();
        assertTrue(all.stream().filter(m -> "directory".equals(m.getMenuType())).count() >= 7);
        assertTrue(all.stream().filter(m -> "menu".equals(m.getMenuType())).count() >= 24);
        for(AdminMenu m:all) if("menu".equals(m.getMenuType())) assertEquals("directory",menus.findById(m.getParentId()).get().getMenuType());
        assertTrue(all.stream().filter(m -> "button".equals(m.getMenuType())).count()>80);
    }
    @Test void everyCatalogButtonRequiresExplicitGrantAndRevokesImmediately() {
        authenticate();
        for (AdminMenu module : menus.findAll()) if ("menu".equals(module.getMenuType()) && !module.getMenuCode().equals("website_security")) {
            assertFalse(permissions.can(module.getMenuCode(),"")); grant(module.getId()); assertTrue(permissions.can(module.getMenuCode(),""));
            for (AdminMenu button : menus.findAll()) if ("button".equals(button.getMenuType()) && module.getId().equals(button.getParentId())) {
                String action=button.getMenuCode().split(":",2)[1];
                assertFalse(permissions.can(module.getMenuCode(),action),button.getMenuCode());
                grant(button.getId());
                boolean reserved=Arrays.asList("manual_order","abnormal_delete","batch_ip","set_agent","unset_agent","defaults").contains(action) || (module.getMenuCode().equals("support") && Arrays.asList("audit", "export").contains(action));
                assertEquals(!reserved,permissions.can(module.getMenuCode(),action),button.getMenuCode());
                String original=button.getStatus(); button.setStatus("disabled"); menus.saveAndFlush(button);
                assertFalse(permissions.can(module.getMenuCode(),action)); button.setStatus(original); menus.saveAndFlush(button);
                grants.findByTenantIdAndRoleId(1L, role.getId()).stream().filter(g -> button.getId().equals(g.getMenuId())).forEach(grants::delete);
                assertFalse(permissions.can(module.getMenuCode(),action), "Revoked: " + button.getMenuCode());
            }
            module.setStatus("disabled"); menus.saveAndFlush(module); assertFalse(permissions.can(module.getMenuCode(),"")); module.setStatus("active"); menus.saveAndFlush(module);
        }
        role.setStatus("inactive"); roles.saveAndFlush(role); assertFalse(permissions.can("users",""));
    }
    @Test void everyAnnotatedEndpointDeniesWithoutItsMenu() {
        authenticate(); int checked=0;
        for (org.springframework.web.method.HandlerMethod handler : mappings.getHandlerMethods().values()) {
            AdminPermission requirement=handler.getMethodAnnotation(AdminPermission.class); if(requirement==null) continue;
            MockHttpServletRequest req=new MockHttpServletRequest("POST","/api/admin/test");
            assertThrows(AccessDeniedException.class,()->guard.preHandle(req,new MockHttpServletResponse(),handler),handler.toString()); checked++;
        }
        assertTrue(checked>=85,"Endpoint coverage: "+checked);
    }
    @Test void roleReadOnlyCannotCreateEditOrDeleteAndGrantEnablesOnlyOneButton() throws Exception {
        grant("durations");
        assertEquals(200,call("GET","/api/admin/durations",null,token));
        String body="{\"duration\":12345,\"label\":\"qa\",\"sortOrder\":999,\"lossRate\":1,\"profitRate\":0.2,\"enabled\":true}";
        assertEquals(403,call("POST","/api/admin/durations",body,token));
        grant("durations:create"); assertEquals(200,call("POST","/api/admin/durations",body,token));
        Long id=durations.findAllByTenantId(1L).stream().filter(d->d.getDuration()==12345).findFirst().get().getId();
        assertEquals(403,call("PUT","/api/admin/durations/"+id,body,token));
        assertEquals(403,call("DELETE","/api/admin/durations/"+id,null,token));
        grant("durations:edit"); assertEquals(200,call("PUT","/api/admin/durations/"+id,body,token));
        grant("durations:delete"); assertEquals(200,call("DELETE","/api/admin/durations/"+id,null,token));
        assertEquals(403,call("GET","/api/admin/statistics",null,token));
        assertEquals(401,mvc.perform(get("/api/admin/durations")).andReturn().getResponse().getStatus());
    }
    @Test void dynamicBodyActionsAndBatchConfigCannotBypassGuards() throws Exception {
        grant("users"); grant("orders"); grant("settings");
        assertEquals(403,call("POST","/api/admin/users/updateStatus","{\"userId\":1,\"status\":\"frozen\"}",token));
        assertEquals(403,call("POST","/api/admin/users/updateBalance","{\"userId\":1,\"fundBalance\":1}",token));
        assertEquals(403,call("POST","/api/admin/orders/option/1/preset-profit","{\"presetType\":\"PROFIT\"}",token));
        assertEquals(403,call("POST","/api/admin/config/save","{\"key\":\"share.templates\",\"value\":\"[]\"}",token));
        grant("settings:save");
        assertEquals(403,call("POST","/api/admin/config/saveBatch","[{\"key\":\"qa.safe\",\"value\":\"1\"},{\"key\":\"share.templates\",\"value\":\"[]\"}]",token));
        assertTrue(getJson("/api/admin/config/get?key=qa.safe",superToken).path("value").isNull());
        assertEquals(403,call("POST","/api/admin/config/save","{\"key\":\"agent.default.permissions\",\"value\":\"{}\"}",token));
    }
    @Test void independentShareMenuDoesNotGrantGeneralSettingsOrWriteWithoutButton() throws Exception {
        grant("share_templates");
        assertEquals("directory", menus.findById(menu("share_templates").getParentId()).get().getMenuType());
        assertEquals("/share-templates", menu("share_templates").getPath());
        assertEquals(200, call("GET", "/api/admin/config/get?key=share.templates", null, token));
        assertEquals(200, call("GET", "/api/admin/tenant-policies/share-templates", null, token));
        assertEquals(403, call("GET", "/api/admin/config/get?key=mail.host", null, token));
        assertEquals(403, call("GET", "/api/admin/tenant-policies/settings", null, token));
        String body = "{\"key\":\"share.templates\",\"value\":\"gold,light\"}";
        assertEquals(403, call("POST", "/api/admin/config/save", body, token));
        grant("share_templates:save");
        assertEquals(200, call("POST", "/api/admin/config/save", body, token));
        assertEquals(403, call("POST", "/api/admin/config/save", "{\"key\":\"mail.host\",\"value\":\"blocked\"}", token));
    }
    @Test void shareMaterialLibraryPersistsCopiesAndRequiresItsOwnPermissionAndPolicy() throws Exception {
        assertEquals(403, call("GET", "/api/admin/share-materials", null, token));
        grant("share_templates"); assertEquals(200, call("GET", "/api/admin/share-materials", null, token));
        String layer = "{\"id\":\"layer-test\",\"name\":\"QA shape\",\"type\":\"roundRect\",\"x\":0,\"y\":0,\"width\":200,\"height\":80,\"fill\":\"#dce9df\",\"stroke\":\"#17804c\",\"strokeWidth\":3,\"radius\":20,\"opacity\":1,\"visible\":true}";
        String body = "{\"name\":\"QA library fixture\",\"layer\":" + layer + "}";
        assertEquals(403, call("POST", "/api/admin/share-materials", body, token)); grant("share_templates:save");
        MvcResult created = mvc.perform(post("/api/admin/share-materials").header("Authorization", "Bearer " + token).contentType("application/json").content(body)).andReturn();
        assertEquals(200, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        String id = json.readTree(created.getResponse().getContentAsString()).path("id").asText();
        assertTrue(getJson("/api/admin/share-materials", token).toString().contains(id));
        String image = layer.replace("roundRect", "image").replace("\"visible\":true", "\"visible\":true,\"src\":\"/api/uploads/images/1/staff/" + actor.getId() + "/fixture.png\",\"fit\":\"contain\"");
        assertEquals(200, call("POST", "/api/admin/share-materials", "{\"name\":\"Owned image\",\"layer\":" + image + "}", token));
        assertEquals(403, call("POST", "/api/admin/share-materials", "{\"name\":\"Private image\",\"layer\":" + image.replace("/staff/" + actor.getId() + "/", "/staff/999999/") + "}", token));
        assertEquals(403, call("POST", "/api/admin/share-materials", "{\"name\":\"Other tenant\",\"layer\":" + image.replace("/images/1/", "/images/2/") + "}", token));
        assertEquals(200, call("DELETE", "/api/admin/share-materials/" + id, null, token));
        assertFalse(getJson("/api/admin/share-materials", token).toString().contains(id));
        JsonNode stored = json.readTree(getJson("/api/admin/config/get?key=share.materials", token).path("value").asText());
        assertTrue(stored.path("materials").findValues("id").stream().anyMatch(node -> id.equals(node.asText())), "Draft image references survive library removal");
        com.gtcfesk.exchange.control.TenantPolicy lock = new com.gtcfesk.exchange.control.TenantPolicy(); lock.setTenantId(1L); lock.setKey("config.share.materials"); lock.setValue("DENY"); lock.setLocked(true); tenantPolicies.saveAndFlush(lock);
        try { assertEquals(403, call("POST", "/api/admin/share-materials", body, token)); }
        finally { tenantPolicies.delete(lock); }
        assertEquals(403, call("POST", "/api/admin/config/save", "{\"key\":\"mail.host\",\"value\":\"blocked\"}", token));
    }
    @Test void rolesRejectOrphanButtonsAndCannotEscalateOrMutateSuper() throws Exception {
        grant("roles"); grant("roles:assign_permission"); grant("roles:create");
        String target=String.valueOf(role.getId());
        assertEquals(403,call("POST","/api/admin/roles/"+target+"/menus","{\"menuIds\":["+menu("settings").getId()+","+menu("settings:save").getId()+"]}",token));
        assertFalse(getJson("/api/admin/roles/"+target+"/menus",superToken).path("menuIds").toString().contains("-999"));
        assertEquals(403,call("POST","/api/admin/admins","{\"role\":\"super_admin\"}",token));
        assertEquals(403,call("PUT","/api/admin/website-security","{}",token));
        assertEquals(403,call("POST","/api/admin/orders/contract/manual","{}",token));
    }
    @Test void agentActionRevocationClearsOmittedActionsAndPreservesTenantScope() throws Exception {
        UserAccount agent=new UserAccount(); agent.setEmail("qa_"+UUID.randomUUID()+"@example.invalid"); agent.setUserType("agent"); agent.setPasswordHash("hash"); agent.setStatus("normal"); agent.setCurrentToken(UUID.randomUUID().toString()); agent=users.saveAndFlush(agent);backendNames.register("AGENT",agent.getId(),agent.getEmail());
        Long module=menu("users").getId();
        String path="/api/admin/users/"+agent.getId()+"/menus";
        assertEquals(200,call("POST",path,"{\"menuIds\":["+module+"],\"actions\":{\""+module+"\":[\"modify_remark\"]}}",superToken));
        assertTrue(actions.existsByTenantIdAndUserIdAndMenuIdAndActionCode(1L, agent.getId(),module,"modify_remark"));
        assertEquals(200,call("POST",path,"{\"menuIds\":["+module+"],\"actions\":{}}",superToken));
        assertTrue(actions.findByTenantIdAndUserId(1L, agent.getId()).isEmpty());
        Map<String,Object> claims=new HashMap<>();claims.put("userType","agent");claims.put("sid",agent.getCurrentToken());claims.put("credential",jwt.credentialKey(agent.getPasswordHash()));claims.put("id",agent.getId());
        String at=jwt.generateToken("agent-"+agent.getId(),claims);
        JsonNode current=getJson("/api/admin/menus/current",at);assertEquals(1,current.path("menus").size()); assertEquals(1,current.path("groups").size());
        assertEquals(403,call("POST","/api/admin/users/999999/update-remark","{\"remark\":\"blocked\"}",at));
        UserMenu campaignMenu=new UserMenu(); campaignMenu.setUserId(agent.getId()); campaignMenu.setMenuId(menu("announcement").getId()); userMenus.saveAndFlush(campaignMenu);
        assertEquals(403,call("GET","/api/admin/activities",null,at));
        assertEquals(403,call("POST","/api/admin/config/save","{\"key\":\"qa\",\"value\":\"1\"}",at));
    }
}
