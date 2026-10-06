package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DepositCustomerSearchTest {
 @Test void suggestionsAreScopedLimitedAndPermissionChecked() {
  UserAccountRepository users=mock(UserAccountRepository.class);
  BackendAccess access=mock(BackendAccess.class);
  DepositOrderController controller=new DepositOrderController(null,null,null,users,null,access,null,null);
  UserAccount user=new UserAccount();user.setId(700L);user.setEmail("alice@example.com");
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent_42","",Collections.singletonList(new SimpleGrantedAuthority("ROLE_AGENT"))));
  try {
   when(users.findDepositCustomers(eq(42L),anyString(),anyString(),any(Pageable.class))).thenReturn(new PageImpl<>(Collections.singletonList(user)));
   assertEquals(700L,controller.customers(" ALICE ").get(0).get("userId"));
   verify(users).findDepositCustomers(42L,"ALICE%","%alice%",PageRequest.of(0,20));
   controller.customers("70");
   verify(users).findDepositCustomers(42L,"70%","%70%",PageRequest.of(0,20));
   controller.customers("a_%");
   verify(users).findDepositCustomers(42L,"a!_!%%","%a!_!%%",PageRequest.of(0,20));
   assertTrue(controller.customers(" ").isEmpty());
   doThrow(new AccessDeniedException("denied")).when(access).checkDeposit("view_deposit_orders");
   clearInvocations(users);
   assertThrows(AccessDeniedException.class,()->controller.customers("alice"));
   verifyNoInteractions(users);
   assertEquals("alice@example.com",controller.manualCustomers(" ALICE ").get(0).get("email"));
   verify(access).checkDeposit("manual_deposit");
   verify(users).findDepositCustomers(42L,"ALICE%","%alice%",PageRequest.of(0,20));
   controller.manualCustomers("70");
   verify(users).findDepositCustomers(42L,"70%","%70%",PageRequest.of(0,20));
   controller.manualCustomers("a_%");
   verify(users).findDepositCustomers(42L,"a!_!%%","%a!_!%%",PageRequest.of(0,20));
   assertTrue(controller.manualCustomers(" ").isEmpty());
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.manualCustomers(String.join("",Collections.nCopies(255,"a"))));
   doThrow(new AccessDeniedException("denied")).when(access).checkDeposit("manual_deposit");
   clearInvocations(users);
   assertThrows(AccessDeniedException.class,()->controller.manualCustomers("alice"));
   verifyNoInteractions(users);
  } finally {SecurityContextHolder.clearContext();}
 }
}
