package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
@Service @RequiredArgsConstructor
public class ControlSecurityService {
 private final ControlAdminRepository admins;private final ControlMfa mfa;private final PasswordEncoder passwords;private final ControlAuditService audit;
 @Transactional public void changeMfa(Input input){ControlAdmin a=admins.lock(ControlIdentity.actorId()).orElseThrow(ControlService::invalid);if(input.password==null||!passwords.matches(input.password,a.getPasswordHash())||!a.isMfaEnabled()||!mfa.verify(a.getMfaSecret(),input.totp))throw ControlService.invalid();if(input.newSecret==null||!input.newSecret.matches("[A-Z2-7]{32,64}"))throw new IllegalArgumentException("新密钥格式无效");String encrypted=mfa.encrypt(input.newSecret);if(!mfa.verify(encrypted,input.newTotp))throw new IllegalArgumentException("新动态码错误");a.setMfaSecret(encrypted);a.setMfaEnabled(true);a.setSessionVersion(a.getSessionVersion()+1);audit.record(a.getId(),null,null,"MFA_CHANGED",String.valueOf(a.getId()),"SUCCESS","All existing sessions revoked",null);}
 public static class Input{public String password,totp,newSecret,newTotp;}
}
