package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.config.JwtFilter;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackendSharePreviewBoundaryTest {
    private static final List<String> READS = Arrays.asList("/api/user/system/timezone", "/api/market/search",
            "/api/market/kline/BTCUSDT", "/api/market/kline/JPY%3DX", "/api/market/kline/JPY=X", "/api/market/kline/%5EGSPC");
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantRequestFilter boundary = new TenantRequestFilter(new TenantHostService(tenants, "example.test",
            "https://admin.example.test", "https://control.example.test", ""));
    private final JwtUtil tokens = mock(JwtUtil.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AdminUserRepository admins = mock(AdminUserRepository.class);
    private final JwtFilter jwt = new JwtFilter(tokens, users, admins);
    private final DefaultClaims claims = new DefaultClaims();

    @BeforeEach void setup() {
        Tenant tenant = new Tenant(); tenant.setId(2L);
        TenantPolicyService policy = mock(TenantPolicyService.class); when(policy.current()).thenReturn(tenant);
        ReflectionTestUtils.setField(jwt, "policy", policy);
        BackendLoginRegistry registry = mock(BackendLoginRegistry.class);
        when(registry.active(anyString(), eq(8L))).thenReturn(true); ReflectionTestUtils.setField(jwt, "logins", registry);
        ControlService control = mock(ControlService.class);
        when(control.validateAccess(any())).thenReturn(new ControlIdentity(8L, 2L, "access-session"));
        ReflectionTestUtils.setField(jwt, "control", control);
        ReflectionTestUtils.setField(jwt, "audit", mock(ControlAuditService.class));
        AdminUser admin = new AdminUser(); admin.setId(8L); admin.setEnabled(true); admin.setRole("admin");
        admin.setCurrentToken("session"); admin.setPasswordHash("hash");
        when(admins.findByTenantIdAndId(2L, 8L)).thenReturn(Optional.of(admin));
        UserAccount agent = new UserAccount(); agent.setId(8L); agent.setStatus("normal"); agent.setUserType("agent");
        agent.setCurrentToken("session"); agent.setPasswordHash("hash");
        when(users.findByTenantIdAndId(2L, 8L)).thenReturn(Optional.of(agent));
        when(tokens.credentialKey("hash")).thenReturn("credential"); when(tokens.parse("test")).thenReturn(claims);
        claims.setExpiration(new Date(System.currentTimeMillis() + 60000)); claims.put("tenantId", 2L);
        claims.put("tenantVersion", 0L); claims.put("sid", "session"); claims.put("credential", "credential");
        identity("admin");
    }
    @AfterEach void cleanup() { assertNull(TenantContext.currentTenantId()); SecurityContextHolder.clearContext(); }
    private void identity(String type) { claims.setSubject(type + "-8"); claims.put("userType", type); }
    private MockHttpServletResponse call(String host, String path, String origin, boolean authenticated) throws Exception {
        return call("GET", host, path, origin, authenticated);
    }
    private MockHttpServletResponse call(String method, String host, String path, String origin, boolean authenticated) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest(method, path); request.addHeader("Host", host);
        if (origin != null) request.addHeader("Origin", origin);
        if (authenticated) request.addHeader("Authorization", "Bearer test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boundary.doFilter(request, response, (r, s) -> jwt.doFilter(r, s, (verified, out) ->
                out.getWriter().write("tenant=" + TenantContext.requireTenantId())));
        assertNull(TenantContext.currentTenantId());
        return response;
    }
    @Test void previewReadsUseSignedTenantOnSharedAdminHostForEveryBackendIdentity() throws Exception {
        for (String type : Arrays.asList("admin", "agent", "control_access")) {
            identity(type);
            for (String path : READS) {
                assertTrue(TenantRequestFilter.backendSharedPath(path));
                MockHttpServletResponse response = call("admin.example.test", path, "https://admin.example.test", true);
                assertEquals(200, response.getStatus(), type + " " + path);
                assertEquals("tenant=2", response.getContentAsString());
            }
        }
        verifyNoInteractions(tenants);
    }
    @Test void previewReadsRejectMissingIdentityWrongOriginUnknownAndControlHosts() throws Exception {
        when(tenants.findByFrontendHost(anyString())).thenReturn(Optional.empty());
        for (String path : READS) {
            assertEquals(403, call("admin.example.test", path, null, false).getStatus());
            assertEquals(403, call("admin.example.test", path, "https://other.example.test", true).getStatus());
            assertEquals(403, call("unknown.example.test", path, null, true).getStatus());
            assertEquals(403, call("control.example.test", path, null, true).getStatus());
        }
        verifyNoInteractions(admins, users);
    }
    @Test void exceptionDoesNotAllowOtherMarketUserOrWriteRoutes() throws Exception {
        when(tenants.findByFrontendHost(anyString())).thenReturn(Optional.empty());
        for (String path : READS) for (String method : Arrays.asList("POST", "PUT", "PATCH", "DELETE"))
            assertEquals(403, call(method, "admin.example.test", path, null, true).getStatus());
        // Encoded route names must not turn the symbol read exception into a batch POST exception.
        assertEquals(403, call("POST", "admin.example.test", "/api/market/kline/%62atch", null, true).getStatus());
        for (String path : Arrays.asList("/api/user/profile", "/api/user/share-templates", "/api/user/system/timezone/extra",
                "/api/market/symbols", "/api/market/search/extra", "/api/market/kline/batch",
                "/api/market/kline/history/BTCUSDT", "/api/market/kline/BTCUSDT/extra", "/api/market/kline/")) {
            assertFalse(TenantRequestFilter.backendSharedPath(path), path);
            assertEquals(403, call("admin.example.test", path, null, true).getStatus());
        }
        verifyNoInteractions(admins, users);
    }
}
