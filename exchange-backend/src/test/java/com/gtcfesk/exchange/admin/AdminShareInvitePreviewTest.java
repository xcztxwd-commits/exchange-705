package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantOneFixture;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(TenantOneFixture.class)
class AdminShareInvitePreviewTest {
    private final AdminUserService users = mock(AdminUserService.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantHostService hosts = mock(TenantHostService.class);
    private final AdminUserController controller = new AdminUserController();
    private final UserAccount user = new UserAccount();
    private final Tenant tenant = new Tenant();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(controller, "adminUserService", users);
        ReflectionTestUtils.setField(controller, "tenants", tenants);
        ReflectionTestUtils.setField(controller, "tenantHosts", hosts);
        user.setId(700L); user.setTenantId(1L); user.setNickname("Alice"); user.setEmail("alice@example.com");
        user.setMyInviteCode("OWN700"); user.setInviteCode("PARENT"); user.setPasswordHash("private"); user.setParentUserId(42L);
        tenant.setId(1L); tenant.setFrontendHost("tenant.example.com"); tenant.setDomainVerified(true);
        when(users.getUserDetail(700L)).thenReturn(user);
        when(tenants.findById(1L)).thenReturn(Optional.of(tenant));
        when(hosts.frontendOrigin("tenant.example.com")).thenReturn("https://tenant.example.com");
    }

    @Test void returnsOnlySelectedUsersOwnInviteAndVerifiedTenantFrontend() throws Exception {
        ResponseEntity<?> response = controller.invitePreview(700L, null);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(200, response.getStatusCodeValue());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(700L, body.get("userId"));
        assertEquals("OWN700", body.get("inviteCode"));
        assertEquals("https://tenant.example.com/?register=1&invite=OWN700", body.get("inviteUrl"));
        assertEquals(new HashSet<>(Arrays.asList("userId", "nickname", "email", "inviteCode", "inviteUrl")), body.keySet());
        assertFalse(body.toString().contains("PARENT")); assertFalse(body.toString().contains("private"));
        AdminPermission permission = AdminUserController.class.getMethod("invitePreview", Long.class, String.class).getAnnotation(AdminPermission.class);
        assertEquals("users", permission.menu()); assertEquals("", permission.action());
        verify(tenants).findById(TenantContext.requireTenantId());
        verify(users, never()).updateInviteCode(anyLong(), anyString());
    }

    @Test void agentCanOnlyPreviewOwnSubordinate() {
        assertEquals(200, controller.invitePreview(700L, "Bearer mock-agent-42").getStatusCodeValue());
        clearInvocations(tenants, hosts);
        assertEquals(403, controller.invitePreview(700L, "Bearer mock-agent-43").getStatusCodeValue());
        verifyNoInteractions(tenants, hosts);
    }

    @Test void crossTenantAndMissingUsersCannotReturnAnInvite() {
        ReflectionTestUtils.setField(user, "tenantId", 2L);
        assertThrows(AccessDeniedException.class, () -> controller.invitePreview(700L, null));
        when(users.getUserDetail(701L)).thenThrow(new IllegalArgumentException("用户不存在"));
        assertThrows(IllegalArgumentException.class, () -> controller.invitePreview(701L, null));
        verifyNoInteractions(tenants, hosts);
    }

    @Test void absentInviteDoesNotInventOrPersistACode() {
        user.setMyInviteCode(" ");
        assertThrows(BusinessException.class, () -> controller.invitePreview(700L, null));
        verifyNoInteractions(tenants, hosts);
        verify(users, never()).updateInviteCode(anyLong(), anyString());
    }

    @Test void unverifiedOrMissingDomainCannotFallBackToAdminOrigin() {
        tenant.setDomainVerified(false);
        assertThrows(BusinessException.class, () -> controller.invitePreview(700L, null));
        tenant.setDomainVerified(true); tenant.setFrontendHost(null);
        assertThrows(BusinessException.class, () -> controller.invitePreview(700L, null));
        when(tenants.findById(1L)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> controller.invitePreview(700L, null));
        verifyNoInteractions(hosts);
    }

    @Test void invitationQueryIsEncoded() {
        user.setMyInviteCode("A+B&x=1");
        assertEquals("https://tenant.example.com/?register=1&invite=A%2BB%26x%3D1",
                ((Map<?, ?>) controller.invitePreview(700L, null).getBody()).get("inviteUrl"));
    }
}
