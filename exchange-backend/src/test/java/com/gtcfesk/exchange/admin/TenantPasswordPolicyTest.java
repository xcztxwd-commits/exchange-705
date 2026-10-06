package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class TenantPasswordPolicyTest {
    private final PasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private final AdminUserRepository admins = mock(AdminUserRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final BackendLoginRegistry logins = mock(BackendLoginRegistry.class);

    private AdminUser admin() {
        AdminUser admin = new AdminUser();
        admin.setId(7L); admin.setAccount("tenant.owner"); admin.setRole("super_admin");
        admin.setPasswordHash(passwords.encode("old-password"));
        admin.setCurrentToken("old-session"); admin.setMustChangePassword(true);
        when(admins.findByTenantIdAndId(1L,7L)).thenReturn(Optional.of(admin));
        return admin;
    }

    private AdminManagementController management() {
        AdminPermissionService permissions = mock(AdminPermissionService.class);
        when(permissions.isSuper()).thenReturn(true);
        AdminManagementController controller = new AdminManagementController(admins,passwords,
            mock(AdminRoleRepository.class),mock(AdminRoleMenuRepository.class),permissions);
        ReflectionTestUtils.setField(controller,"logins",logins);
        return controller;
    }

    @Test void adminChangeAllowsSixDigitPasswordsAndRejectsShortOrOversizedPasswords() {
        AdminUser admin = admin();
        AdminAuthService service = new AdminAuthService(admins,users,passwords,null);
        for (String invalid : new String[]{null,"12345",new String(new char[129]).replace('\0','1')}) {
            assertThrows(BusinessException.class,()->service.changePassword(7L,"old-password",invalid));
            assertEquals("old-session",admin.getCurrentToken());
        }
        assertThrows(BusinessException.class,()->service.changePassword(7L,"wrong-old-password","123456"));
        service.changePassword(7L,"old-password","123456");
        assertTrue(passwords.matches("123456",admin.getPasswordHash()));
        assertNull(admin.getCurrentToken()); assertFalse(admin.isMustChangePassword());
        verify(admins,times(1)).save(admin);
    }

    @Test void agentChangeUsesSameNumericMinimum() {
        UserAccount agent = new UserAccount(); agent.setId(8L); agent.setUserType("agent");
        agent.setPasswordHash(passwords.encode("old-password")); agent.setCurrentToken("old-session");
        when(users.findByTenantIdAndId(1L,8L)).thenReturn(Optional.of(agent));
        AdminAuthService service = new AdminAuthService(admins,users,passwords,null);
        assertThrows(BusinessException.class,()->service.changeAgentPassword(8L,"old-password","12345"));
        service.changeAgentPassword(8L,"old-password","1234567");
        assertTrue(passwords.matches("1234567",agent.getPasswordHash())); assertNull(agent.getCurrentToken());
        verify(users,times(1)).save(agent);
    }

    @Test void tenantAdminCreationAllowsNumericSixAndRejectsFive() {
        AdminManagementController controller = management();
        AdminManagementController.CreateAdminRequest input = new AdminManagementController.CreateAdminRequest();
        input.setAccount("new.owner"); input.setEmail("owner@example.test"); input.setRole("super_admin");
        input.setPassword("12345");
        UsernamePasswordAuthenticationToken actor = new UsernamePasswordAuthenticationToken("7","unused");
        assertThrows(BusinessException.class,()->controller.createAdmin(actor,input));
        input.setPassword("123456");
        when(admins.saveAndFlush(any())).thenAnswer(call->{AdminUser value=call.getArgument(0);value.setId(9L);return value;});
        assertEquals(200,controller.createAdmin(actor,input).getStatusCodeValue());
        verify(admins).saveAndFlush(argThat(value->passwords.matches("123456",value.getPasswordHash()) && value.isMustChangePassword()));
    }

    @Test void tenantAdminResetAllowsNumericSixAndStillRequiresInitialPasswordChange() {
        AdminUser admin = admin();
        AdminManagementController controller = management();
        AdminManagementController.UpdateAdminRequest input = new AdminManagementController.UpdateAdminRequest();
        UsernamePasswordAuthenticationToken actor = new UsernamePasswordAuthenticationToken("7","unused");
        input.setPassword("12345");
        assertThrows(BusinessException.class,()->controller.updateAdmin(actor,7L,input));
        input.setPassword("123456");
        assertEquals(200,controller.updateAdmin(actor,7L,input).getStatusCodeValue());
        assertTrue(passwords.matches("123456",admin.getPasswordHash()));
        assertTrue(admin.isMustChangePassword()); assertNull(admin.getCurrentToken());
        verify(admins,times(1)).saveAndFlush(admin);
    }

    @Test void controlProvisionedTenantAdminsUseSameNumericMinimum() {
        BackendAccountService service = new BackendAccountService(mock(BackendLoginRepository.class),logins,admins,passwords,null,null);
        BackendAccountService.Input input = new BackendAccountService.Input();
        input.type="ADMIN"; input.account="new.owner"; input.email="owner@example.test";
        input.role="super_admin"; input.reason="Password policy test"; input.password="12345";
        assertThrows(IllegalArgumentException.class,()->service.create(1L,input));
        input.password="123456";
        when(admins.saveAndFlush(any())).thenAnswer(call->{AdminUser value=call.getArgument(0);value.setId(9L);return value;});
        BackendLogin entry = new BackendLogin(); entry.setSubjectType("ADMIN"); entry.setAdminUserId(9L);
        when(logins.register("ADMIN",9L,"new.owner")).thenReturn(entry);
        assertSame(entry,service.create(1L,input));
        verify(admins).saveAndFlush(argThat(value->passwords.matches("123456",value.getPasswordHash()) && value.isMustChangePassword()));
    }
}
