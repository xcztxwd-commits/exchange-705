package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class DepositRecipientTest {
 @Test void lookupByIdAndEmailPreservesPermissions() {
  UserAccountRepository users=mock(UserAccountRepository.class);
  AssetAccountRepository assets=mock(AssetAccountRepository.class);
  BackendAccess access=mock(BackendAccess.class);
  DepositOrderController controller=new DepositOrderController(null,null,null,users,assets,access,null,null);
  UserAccount user=new UserAccount();user.setId(7L);user.setEmail("test@example.com");
  when(users.findByTenantIdAndId(1L, 7L)).thenReturn(Optional.of(user));
  when(users.findByTenantIdAndEmail(1L, "test@example.com")).thenReturn(Optional.of(user));
  when(assets.findByTenantIdAndUserId(1L, 7L)).thenReturn(Collections.emptyList());
  assertEquals(7L,controller.findRecipient(" 7 ").get("userId"));
  assertEquals(7L,controller.findRecipient(" test@example.com ").get("userId"));
  verify(access,times(2)).checkUser(7L);
  assertThrows(ResponseStatusException.class,()->controller.findRecipient("999999999999999999999999"));
  assertThrows(ResponseStatusException.class,()->controller.findRecipient("invalid"));
  assertThrows(ResponseStatusException.class,()->controller.findRecipient("missing@example.com"));
  doThrow(new AccessDeniedException("denied")).when(access).checkUser(7L);
  assertThrows(AccessDeniedException.class,()->controller.findRecipient("test@example.com"));
  doThrow(new AccessDeniedException("denied")).when(access).checkDeposit("manual_deposit");
  clearInvocations(users);
  assertThrows(AccessDeniedException.class,()->controller.findRecipient("test@example.com"));
  verifyNoInteractions(users);
 }
}
