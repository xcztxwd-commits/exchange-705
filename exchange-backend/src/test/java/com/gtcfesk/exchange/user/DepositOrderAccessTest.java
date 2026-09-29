package com.gtcfesk.exchange.user;
import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DepositOrderAccessTest {
 AdminUserRepository admins=mock(AdminUserRepository.class);AdminRoleRepository roles=mock(AdminRoleRepository.class);
 AdminRoleMenuRepository grants=mock(AdminRoleMenuRepository.class);AdminMenuRepository menus=mock(AdminMenuRepository.class);
 UserMenuRepository userMenus=mock(UserMenuRepository.class);UserActionRepository actions=mock(UserActionRepository.class);UserAccountRepository users=mock(UserAccountRepository.class);
 BackendAccess access=new BackendAccess(admins,roles,grants,menus,userMenus,actions,users,mock(DepositRecordRepository.class),mock(WithdrawRecordRepository.class),mock(LoanRecordRepository.class),mock(KycRecordRepository.class),mock(LoanPersonalInfoRepository.class),mock(ContractOrderRepository.class),mock(OptionOrderRepository.class),mock(FinancialOrderRepository.class),new ObjectMapper(),new AdminPermissionService(admins,roles,grants,menus,userMenus,actions));
 void auth(String id,String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id,null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_"+role))));}
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 List<AdminMenu> catalog=new ArrayList<>();
 void menu(String code,long id){AdminMenu m=new AdminMenu();m.setId(id);m.setMenuCode(code);m.setMenuType("menu");m.setPath("/"+code);catalog.add(m);when(menus.findByStatusOrderBySortOrderAsc("active")).thenReturn(catalog);when(menus.findByMenuCode(code)).thenReturn(Optional.of(m));
  m.setMenuType("menu");m.setPath("/"+code);m.setStatus("active");
  AdminMenu button=new AdminMenu();button.setId(id+1);button.setParentId(id);button.setMenuType("button");button.setMenuCode(code+":manual_deposit");button.setStatus("active");
  when(menus.findByStatusOrderBySortOrderAsc("active")).thenReturn(Arrays.asList(m,button));
 }
 @Test void anonymousUserAndUnassignedAdminDenied(){assertThrows(RuntimeException.class,()->access.checkDeposit("manual_deposit"));auth("1","USER");assertThrows(RuntimeException.class,()->access.checkDeposit("manual_deposit"));auth("1","ADMIN");assertThrows(RuntimeException.class,()->access.checkDeposit("manual_deposit"));}
 @Test void parentMenuIsNotManualOrExportGrant(){auth("1","ADMIN");menu("deposit_orders",10);AdminUser a=new AdminUser();a.setRole("ops");when(admins.findById(1L)).thenReturn(Optional.of(a));AdminRole r=new AdminRole();r.setId(1L);r.setStatus("active");when(roles.findByRoleCode("ops")).thenReturn(Optional.of(r));AdminRoleMenu g=new AdminRoleMenu();g.setMenuId(10L);when(grants.findByRoleId(1L)).thenReturn(Collections.singletonList(g));assertThrows(RuntimeException.class,()->access.checkDeposit("manual_deposit"));assertThrows(RuntimeException.class,()->access.checkDeposit("export_deposit_orders"));}
 @Test void agentRequiresActionAndCannotReachOtherCustomer(){auth("agent-9","AGENT");menu("deposit_orders",10);UserMenu grant=new UserMenu();grant.setUserId(9L);grant.setMenuId(10L);when(userMenus.findByUserId(9L)).thenReturn(Collections.singletonList(grant));assertThrows(RuntimeException.class,()->access.checkDeposit("manual_deposit"));AdminMenu button=new AdminMenu();button.setId(11L);button.setParentId(10L);button.setMenuCode("deposit_orders:manual_deposit");button.setMenuType("button");catalog.add(button);UserAction action=new UserAction();action.setActionCode("manual_deposit");when(actions.findByUserIdAndMenuId(9L,10L)).thenReturn(Collections.singletonList(action));access.checkDeposit("manual_deposit");UserAccount u=new UserAccount();u.setParentUserId(8L);when(users.findById(7L)).thenReturn(Optional.of(u));assertThrows(RuntimeException.class,()->access.checkUser(7L));u.setParentUserId(9L);access.checkUser(7L);assertThrows(RuntimeException.class,()->access.checkDepositReview("approve_deposit"));}
 @Test void superAdminUsesExistingAuthority(){auth("1","SUPER_ADMIN");access.checkDeposit("manual_deposit");access.checkDepositReview("approve_deposit");}
}
