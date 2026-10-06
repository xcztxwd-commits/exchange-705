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
 @Test void sixDigitCreationWorksWithoutAManualReason(){
  Fixture f=new Fixture();ControlAccountService.Input in=f.newAccountInput();in.reason=null;
  when(f.mfa.encrypt(in.newSecret)).thenReturn("new-encrypted");when(f.mfa.verify("new-encrypted",in.newTotp)).thenReturn(true);when(f.passwords.encode("123456")).thenReturn("six-digit-hash");when(f.admins.saveAndFlush(any())).thenAnswer(call->{ControlAdmin a=call.getArgument(0);a.setId(2L);return a;});
  ControlAdmin created=f.service.create(in);assertEquals("six-digit-hash",created.getPasswordHash());assertTrue(created.isMfaEnabled());assertTrue(created.isEnabled());
  verify(f.audit).record(eq(1L),isNull(),isNull(),eq("CONTROL_ACCOUNT_CREATE"),eq("2"),eq("SUCCESS"),anyString(),eq("总控账号管理"));
 }
 @Test void sixDigitResetWithoutAManualReasonStillRevokesOldSessions(){
  Fixture f=new Fixture();ControlAdmin target=new ControlAdmin();target.setId(2L);target.setSessionVersion(4);when(f.admins.lock(2L)).thenReturn(Optional.of(target));
  ControlAccountService.Input in=f.input();in.reason=" ";in.newPassword="123456";when(f.passwords.encode(in.newPassword)).thenReturn("six-digit-hash");f.service.update(2L,in);
  assertEquals("six-digit-hash",target.getPasswordHash());assertEquals(5,target.getSessionVersion());
  verify(f.audit).record(eq(1L),isNull(),isNull(),eq("CONTROL_ACCOUNT_UPDATE"),eq("2"),eq("SUCCESS"),anyString(),eq("总控账号管理"));
 }
 @Test void createAndResetRejectPasswordsOutsideSixTo128Characters(){
  Fixture f=new Fixture();ControlAdmin target=new ControlAdmin();target.setId(2L);when(f.admins.lock(2L)).thenReturn(Optional.of(target));
  for(String password:Arrays.asList(null,"","12345",String.join("",Collections.nCopies(129,"1")))){
   ControlAccountService.Input in=f.newAccountInput();in.reason=null;in.newPassword=password;assertThrows(IllegalArgumentException.class,()->f.service.create(in));
   // Null means no password change for updates; a state change is still required.
   assertThrows(IllegalArgumentException.class,()->f.service.update(2L,in));
  }
  verify(f.admins,never()).saveAndFlush(any());assertEquals(0,target.getSessionVersion());
 }
 @Test void shorterPasswordDoesNotBypassNewAccountMfa(){
  Fixture f=new Fixture();ControlAccountService.Input in=f.newAccountInput();in.reason=null;in.newSecret="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";assertThrows(IllegalArgumentException.class,()->f.service.create(in));
  in.newSecret="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";when(f.mfa.encrypt(in.newSecret)).thenReturn("new-encrypted");assertThrows(IllegalArgumentException.class,()->f.service.create(in));verify(f.admins,never()).saveAndFlush(any());
 }
 @Test void explicitBootstrapAcceptsSixDigitsButRejectsShortPasswords(){
  Fixture f=new Fixture();String seed="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";when(f.passwords.encode("123456")).thenReturn("six-digit-hash");when(f.mfa.encrypt(seed)).thenReturn("encrypted");
  new ControlBootstrap(f.admins,f.passwords,f.mfa,true,"initialoperator","123456",seed).run();verify(f.admins).saveAndFlush(argThat(a->a.isMfaEnabled()&&"six-digit-hash".equals(a.getPasswordHash())&&"encrypted".equals(a.getMfaSecret())));
  Fixture rejected=new Fixture();assertThrows(IllegalStateException.class,()->new ControlBootstrap(rejected.admins,rejected.passwords,rejected.mfa,true,"initialoperator","12345",seed).run());verify(rejected.admins,never()).saveAndFlush(any());
 }
 static class Fixture {
  ControlAdminRepository admins=mock(ControlAdminRepository.class);PasswordEncoder passwords=mock(PasswordEncoder.class);ControlMfa mfa=mock(ControlMfa.class);ControlAuditService audit=mock(ControlAuditService.class);ControlAdmin actor=new ControlAdmin();ControlAccountService service=new ControlAccountService(admins,passwords,mfa,audit);
  Fixture(){actor.setId(1L);actor.setPasswordHash("actor-hash");actor.setMfaSecret("actor-encrypted");actor.setMfaEnabled(true);when(admins.lock(1L)).thenReturn(Optional.of(actor));when(passwords.matches("current-password","actor-hash")).thenReturn(true);when(mfa.verify("actor-encrypted","654321")).thenReturn(true);UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("1",null,Collections.emptyList());auth.setDetails(new ControlIdentity(1L,null,null));SecurityContextHolder.getContext().setAuthentication(auth);}
  ControlAccountService.Input newAccountInput(){ControlAccountService.Input i=input();i.account="newoperator";i.newPassword="123456";i.newSecret="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";i.newTotp="123456";return i;}
  ControlAccountService.Input input(){ControlAccountService.Input i=new ControlAccountService.Input();i.password="current-password";i.totp="654321";i.reason="account management";return i;}
 }
}
