package com.gtcfesk.exchange.admin;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentProvisioningPolicyTest {
 @AfterEach void clear(){SecurityContextHolder.clearContext();TenantContext.clear();}
 @Test void newAgentRequiresFeatureBeforeMutation(){TenantContext.open(1L);AdminUserService service=new AdminUserService();TenantPolicyService policy=mock(TenantPolicyService.class);UserAccountRepository users=mock(UserAccountRepository.class);AdminPermissionService permissions=mock(AdminPermissionService.class);ReflectionTestUtils.setField(service,"tenantPolicy",policy);ReflectionTestUtils.setField(service,"userAccountRepository",users);ReflectionTestUtils.setField(service,"rolePermissions",permissions);UserAccount user=new UserAccount();user.setId(7L);user.setUserType("normal");when(users.lockById(7L)).thenReturn(Optional.of(user));doThrow(new org.springframework.security.access.AccessDeniedException("disabled")).when(policy).requireNewBusiness("agent");com.gtcfesk.exchange.admin.dto.UpdateUserTypeRequest input=new com.gtcfesk.exchange.admin.dto.UpdateUserTypeRequest();input.setUserId(7L);input.setUserType("agent");assertThrows(RuntimeException.class,()->service.updateUserType(input));verify(permissions).require("users","set_agent");verify(users,never()).saveAndFlush(any());assertEquals("normal",user.getUserType());}
 @Test void agentCannotSelfEscalateAndExistingLoginRenameDoesNotRequireNewBusiness(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent-7",null,Collections.singleton(new SimpleGrantedAuthority("ROLE_AGENT"))));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->new AdminUserService().updateUserType(new com.gtcfesk.exchange.admin.dto.UpdateUserTypeRequest()));SecurityContextHolder.clearContext();TenantContext.open(1L);BackendLoginRepository logins=mock(BackendLoginRepository.class);UserAccountRepository users=mock(UserAccountRepository.class);TenantPolicyService policy=mock(TenantPolicyService.class);BackendLoginRegistry registry=new BackendLoginRegistry(logins,mock(AdminUserRepository.class),users);ReflectionTestUtils.setField(registry,"policy",policy);UserAccount agent=new UserAccount();agent.setUserType("agent");when(users.findByTenantIdAndId(1L,7L)).thenReturn(Optional.of(agent));BackendLogin existing=new BackendLogin();existing.setId(1L);when(logins.findByTenantIdAndUserId(1L,7L)).thenReturn(Optional.of(existing));registry.register("AGENT",7L,"new@example.com");verifyNoInteractions(policy);existing.setEnabled(false);registry.register("AGENT",7L,"new@example.com");verify(policy).requireNewBusiness("agent");}
}
