package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class BackendAccountCollisionTest {
    @Test void controlCreationRejectsAnotherTenantsAdminBeforePersistingSubject() {
        BackendLoginRepository entries=mock(BackendLoginRepository.class);
        AdminUserRepository admins=mock(AdminUserRepository.class);
        PasswordEncoder passwords=mock(PasswordEncoder.class);
        BackendLogin taken=new BackendLogin();taken.setTenantId(2L);taken.setSubjectType("ADMIN");taken.setAdminUserId(7L);
        when(entries.findByNormalizedAccount("reserved.owner")).thenReturn(Optional.of(taken));
        BackendLoginRegistry registry=new BackendLoginRegistry(entries,admins,mock(UserAccountRepository.class));
        BackendAccountService service=new BackendAccountService(entries,registry,admins,passwords,null,null);
        BackendAccountService.Input input=new BackendAccountService.Input();input.type="ADMIN";input.account="RESERVED.OWNER";input.email="test@example.test";input.password="local-test-password";input.reason="collision test";
        assertThrows(IllegalArgumentException.class,()->service.create(1L,input));
        verify(entries).findByNormalizedAccount("reserved.owner");verifyNoInteractions(admins,passwords);
    }
    @Test void ownerCreationRejectsAnotherTenantsAgentBeforePersistingSubject() {
        BackendLoginRepository entries=mock(BackendLoginRepository.class);
        AdminUserRepository admins=mock(AdminUserRepository.class);
        PasswordEncoder passwords=mock(PasswordEncoder.class);
        BackendLogin taken=new BackendLogin();taken.setTenantId(2L);taken.setSubjectType("AGENT");taken.setUserId(8L);
        when(entries.findByNormalizedAccount("reserved.agent")).thenReturn(Optional.of(taken));
        BackendLoginRegistry registry=new BackendLoginRegistry(entries,admins,mock(UserAccountRepository.class));
        AdminManagementController controller=new AdminManagementController(admins,passwords,mock(AdminRoleRepository.class),mock(AdminRoleMenuRepository.class),mock(AdminPermissionService.class));
        ReflectionTestUtils.setField(controller,"logins",registry);
        AdminManagementController.CreateAdminRequest input=new AdminManagementController.CreateAdminRequest();input.setAccount("RESERVED.AGENT");input.setEmail("test@example.test");input.setPassword("local-test-password");input.setRole("admin");
        assertThrows(IllegalArgumentException.class,()->controller.createAdmin(new UsernamePasswordAuthenticationToken("1","unused"),input));
        verify(entries).findByNormalizedAccount("reserved.agent");verifyNoInteractions(admins,passwords);
    }
    @Test void controlCreationTranslatesConcurrentUniqueConflictWithoutReturningDatabaseDetails() {
        BackendLoginRepository entries=mock(BackendLoginRepository.class);AdminUserRepository admins=mock(AdminUserRepository.class);
        when(entries.findByNormalizedAccount("new.owner")).thenReturn(Optional.empty());
        when(admins.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate entry sensitive-account"));
        BackendAccountService service=new BackendAccountService(entries,new BackendLoginRegistry(entries,admins,mock(UserAccountRepository.class)),admins,mock(PasswordEncoder.class),null,null);
        BackendAccountService.Input input=new BackendAccountService.Input();input.type="ADMIN";input.account="new.owner";input.email="test@example.test";input.password="local-test-password";input.reason="collision test";
        IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->service.create(1L,input));
        assertEquals("账号或邮箱不可用",failure.getMessage());assertNull(failure.getCause());verify(entries,never()).saveAndFlush(any());
    }

    @Test void ownerCreationTranslatesConcurrentUniqueConflictWithoutPersistingLogin() {
        BackendLoginRepository entries=mock(BackendLoginRepository.class);AdminUserRepository admins=mock(AdminUserRepository.class);
        when(entries.findByNormalizedAccount("new.owner")).thenReturn(Optional.empty());
        when(admins.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate entry sensitive-account"));
        AdminRoleRepository roles=mock(AdminRoleRepository.class);com.gtcfesk.exchange.entity.AdminRole role=new com.gtcfesk.exchange.entity.AdminRole();role.setId(4L);role.setRoleCode("admin");role.setStatus("active");
        when(roles.findByTenantIdAndRoleCode(1L,"admin")).thenReturn(Optional.of(role));
        AdminManagementController controller=new AdminManagementController(admins,mock(PasswordEncoder.class),roles,mock(AdminRoleMenuRepository.class),mock(AdminPermissionService.class));
        ReflectionTestUtils.setField(controller,"logins",new BackendLoginRegistry(entries,admins,mock(UserAccountRepository.class)));
        AdminManagementController.CreateAdminRequest input=new AdminManagementController.CreateAdminRequest();input.setAccount("new.owner");input.setEmail("test@example.test");input.setPassword("local-test-password");input.setRole("admin");
        com.gtcfesk.exchange.common.BusinessException failure=assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->controller.createAdmin(new UsernamePasswordAuthenticationToken("1","unused"),input));
        assertEquals("账号或邮箱不可用",failure.getMessage());assertNull(failure.getCause());verify(entries,never()).saveAndFlush(any());
    }

    @Test void ownerRenameRejectsAnotherTenantBeforeDirtyingCurrentEntity() {
        BackendLoginRepository entries=mock(BackendLoginRepository.class);AdminUserRepository admins=mock(AdminUserRepository.class);AdminPermissionService permissions=mock(AdminPermissionService.class);
        AdminUser current=new AdminUser();current.setId(9L);current.setAccount("own.owner");current.setRole("admin");
        when(admins.findByTenantIdAndId(1L,9L)).thenReturn(Optional.of(current));when(permissions.isSuper()).thenReturn(true);
        BackendLogin taken=new BackendLogin();taken.setTenantId(2L);taken.setAdminUserId(7L);taken.setSubjectType("ADMIN");
        when(entries.findByNormalizedAccount("reserved.owner")).thenReturn(Optional.of(taken));
        AdminManagementController controller=new AdminManagementController(admins,mock(PasswordEncoder.class),mock(AdminRoleRepository.class),mock(AdminRoleMenuRepository.class),permissions);
        ReflectionTestUtils.setField(controller,"logins",new BackendLoginRegistry(entries,admins,mock(UserAccountRepository.class)));
        AdminManagementController.UpdateAdminRequest input=new AdminManagementController.UpdateAdminRequest();input.setAccount("RESERVED.OWNER");
        assertThrows(IllegalArgumentException.class,()->controller.updateAdmin(new UsernamePasswordAuthenticationToken("1","unused"),9L,input));
        assertEquals("own.owner",current.getAccount());verify(admins,never()).saveAndFlush(any());verify(entries,never()).saveAndFlush(any());
    }
    @Test void ownNormalizedUnchangedNameDoesNotCollideWithItself() {
        BackendLoginRepository entries=mock(BackendLoginRepository.class);AdminUserRepository admins=mock(AdminUserRepository.class);AdminPermissionService permissions=mock(AdminPermissionService.class);
        AdminUser current=new AdminUser();current.setId(9L);current.setAccount("own.owner");current.setRole("admin");
        when(admins.findByTenantIdAndId(1L,9L)).thenReturn(Optional.of(current));when(permissions.isSuper()).thenReturn(true);when(admins.saveAndFlush(current)).thenReturn(current);
        BackendLogin own=new BackendLogin();own.setId(5L);own.setTenantId(1L);own.setAdminUserId(9L);own.setSubjectType("ADMIN");own.setNormalizedAccount("own.owner");
        when(entries.findByTenantIdAndAdminUserId(1L,9L)).thenReturn(Optional.of(own));when(entries.findByNormalizedAccount("own.owner")).thenReturn(Optional.of(own));when(entries.saveAndFlush(own)).thenReturn(own);
        AdminManagementController controller=new AdminManagementController(admins,mock(PasswordEncoder.class),mock(AdminRoleRepository.class),mock(AdminRoleMenuRepository.class),permissions);
        ReflectionTestUtils.setField(controller,"logins",new BackendLoginRegistry(entries,admins,mock(UserAccountRepository.class)));
        AdminManagementController.UpdateAdminRequest input=new AdminManagementController.UpdateAdminRequest();input.setAccount(" OWN.OWNER ");
        assertEquals(200,controller.updateAdmin(new UsernamePasswordAuthenticationToken("1","unused"),9L,input).getStatusCodeValue());
        assertEquals("own.owner",current.getAccount());verify(entries,times(1)).findByNormalizedAccount("own.owner");
    }
}
