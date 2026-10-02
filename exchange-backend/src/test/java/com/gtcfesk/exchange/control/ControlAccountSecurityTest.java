package com.gtcfesk.exchange.control;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ControlAccountSecurityTest {
 static { ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN); }
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
 @Test void privilegedAccountCreationRequiresLiveActorPasswordAndMfa(){
  Fixture f=new Fixture();ControlAccountService.Input in=f.input();in.account="newoperator";in.newPassword="long-enough-new-password";in.newSecret="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";in.newTotp="123456";
  when(f.mfa.verify("actor-encrypted","654321")).thenReturn(false);assertThrows(RuntimeException.class,()->f.service.create(in));verify(f.admins,never()).saveAndFlush(any());
  when(f.mfa.verify("actor-encrypted","654321")).thenReturn(true);when(f.mfa.encrypt(in.newSecret)).thenReturn("new-encrypted");when(f.mfa.verify("new-encrypted","123456")).thenReturn(true);when(f.passwords.encode(in.newPassword)).thenReturn("new-hash");when(f.admins.saveAndFlush(any())).thenAnswer(call->{ControlAdmin a=call.getArgument(0);a.setId(2L);return a;});
  ControlAdmin a=f.service.create(in);assertTrue(a.isMfaEnabled());assertEquals("new-encrypted",a.getMfaSecret());assertEquals("new-hash",a.getPasswordHash());verify(f.audit).record(eq(1L),isNull(),isNull(),eq("CONTROL_ACCOUNT_CREATE"),eq("2"),eq("SUCCESS"),anyString(),eq("account management"));
 }
 @Test void cannotDisableSelfAndResetInvalidatesAllTargetSessions(){
  Fixture f=new Fixture();ControlAccountService.Input in=f.input();in.enabled=false;assertThrows(RuntimeException.class,()->f.service.update(1L,in));assertTrue(f.actor.isEnabled());
  ControlAdmin target=new ControlAdmin();target.setId(2L);target.setSessionVersion(4);when(f.admins.lock(2L)).thenReturn(Optional.of(target));in.newPassword="long-enough-new-password";when(f.passwords.encode(in.newPassword)).thenReturn("new-hash");f.service.update(2L,in);assertFalse(target.isEnabled());assertEquals(5,target.getSessionVersion());assertEquals("new-hash",target.getPasswordHash());
 }
 static class Fixture {
  ControlAdminRepository admins=mock(ControlAdminRepository.class);PasswordEncoder passwords=mock(PasswordEncoder.class);ControlMfa mfa=mock(ControlMfa.class);ControlAuditService audit=mock(ControlAuditService.class);ControlAdmin actor=new ControlAdmin();ControlAccountService service=new ControlAccountService(admins,passwords,mfa,audit);
  Fixture(){actor.setId(1L);actor.setPasswordHash("actor-hash");actor.setMfaSecret("actor-encrypted");actor.setMfaEnabled(true);when(admins.lock(1L)).thenReturn(Optional.of(actor));when(passwords.matches("current-password","actor-hash")).thenReturn(true);when(mfa.verify("actor-encrypted","654321")).thenReturn(true);UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("1",null,Collections.emptyList());auth.setDetails(new ControlIdentity(1L,null,null));SecurityContextHolder.getContext().setAuthentication(auth);}
  ControlAccountService.Input input(){ControlAccountService.Input i=new ControlAccountService.Input();i.password="current-password";i.totp="654321";i.reason="account management";return i;}
 }
}
