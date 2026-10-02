package com.gtcfesk.exchange.demo;

import com.gtcfesk.exchange.config.*;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.admin.AdminUserRepository;
import com.gtcfesk.exchange.entity.UserAccount;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.control.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import javax.servlet.Filter;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Production security chain and JWT filter, with deterministic token parser/owner fixtures. */
@SpringJUnitConfig(DemoSecurityTest.Config.class)
@WebAppConfiguration
@org.springframework.test.context.TestPropertySource(properties = {"jwt.secret=demo-security-fixture-not-a-production-key", "jwt.expireSeconds=60"})
class DemoSecurityTest {
 private TenantContext.Scope tenantScope;
 @AfterEach void closeTenantScope(){ if(tenantScope!=null)tenantScope.close(); }
    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, JwtFilter.class, DemoTradingController.class, DemoModeBoundary.class})
    static class Config {
        @Bean com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound(){return mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);}
        @Bean TenantReadinessService readiness(){return mock(TenantReadinessService.class);}
        @Bean TenantRepository tenants(){return mock(TenantRepository.class);}

        @Bean ControlService control(){return mock(ControlService.class);}
        @Bean TenantPolicyService tenantPolicy(){TenantPolicyService policy=mock(TenantPolicyService.class);Tenant tenant=new Tenant();tenant.setId(1L);when(policy.current()).thenReturn(tenant);return policy;}
        @Bean BackendLoginRegistry logins(){BackendLoginRegistry registry=mock(BackendLoginRegistry.class);when(registry.active(anyString(),anyLong())).thenReturn(true);return registry;}
        @Bean ControlAuditService audit(){return mock(ControlAuditService.class);}

        @Bean JwtUtil jwtUtil() { return mock(JwtUtil.class); }
        @Bean UserAccountRepository users() { return mock(UserAccountRepository.class); }
        @Bean AdminUserRepository admins() { return mock(AdminUserRepository.class); }
        @Bean DemoTradingService demo() { return mock(DemoTradingService.class); }
    }
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtUtil jwt;
    @Autowired UserAccountRepository users;
    @Autowired DemoTradingService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        tenantScope = TenantContext.open(1L);
        reset(jwt, users, service);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        UserAccount user = new UserAccount(); user.setId(7L); user.setStatus("normal"); user.setKycStatus("NOT_VERIFIED");
        user.setCurrentToken("session"); user.setPasswordHash("hash"); user.setUserType("agent");
        when(users.findByTenantIdAndId(1L, 7L)).thenReturn(Optional.of(user)); when(jwt.credentialKey("hash")).thenReturn("credential");
        for (String type : Arrays.asList("user", "agent")) {
            DefaultClaims claims = new DefaultClaims(); claims.setSubject(type + "-7"); claims.setExpiration(new Date(System.currentTimeMillis() + 60000));
            claims.put("tenantId",1L); claims.put("tenantVersion",0L); claims.put("userType", type); claims.put("sid", "session"); claims.put("credential", "credential");
            when(jwt.parse(type)).thenReturn(claims);
        }
        when(service.initialize(7L)).thenReturn(new DemoAccount());
    }
    @Test void anonymousDenied() throws Exception {
        mvc.perform(post("/api/demo/account")).andExpect(status().isUnauthorized()); verifyNoInteractions(service);
    }
    @Test void unverifiedUserCanInitializeWithoutChangingIdentity() throws Exception {
        mvc.perform(post("/api/demo/account").header("Authorization", "Bearer user").header("X-Account-Mode", "DEMO"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).initialize(7L);
    }
    @Test void agentTokenDeniedEvenWhenItReferencesExistingUser() throws Exception {
        mvc.perform(post("/api/demo/account").header("Authorization", "Bearer agent")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void invalidAmountsNeverReachService() throws Exception {
        for (String amount : Arrays.asList("-1", "0", "100001", "10.001"))
            mvc.perform(post("/api/demo/orders").header("Authorization", "Bearer user").contentType("application/json")
                .content("{\"generation\":1,\"symbol\":\"BTCUSDT\",\"requestKey\":\"12345678-1234-1234-1234-123456789012\",\"amount\":" + amount + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
