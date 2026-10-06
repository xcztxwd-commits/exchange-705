package com.gtcfesk.exchange.control;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Total-control credentials never live in tenant admin tables. Sensitive changes reverify MFA. */
@Service @RequiredArgsConstructor
public class ControlAccountService {
 private final ControlAdminRepository admins;
 private final PasswordEncoder passwords;
 private final ControlMfa mfa;
 private final ControlAuditService audit;
 @Transactional(readOnly=true) public Page<ControlAdmin> list(int page){ControlIdentity.actorId();return admins.findAll(PageRequest.of(Math.max(0,page),50,Sort.by("id")));}
 @Transactional public ControlAdmin create(Input input){
  ControlAdmin actor=reauth(input);String account=BackendLoginRegistry.normalize(input.account);
  if(account.length()>64||admins.findByAccount(account).isPresent())throw new IllegalArgumentException("总控账号不可用");
  validatePassword(input.newPassword);
  if(input.newSecret==null||!input.newSecret.matches("[A-Z2-7]{32,64}"))throw new IllegalArgumentException("新密钥格式无效");
  String secret=mfa.encrypt(input.newSecret);if(!mfa.verify(secret,input.newTotp))throw new IllegalArgumentException("新动态码错误");
  ControlAdmin created=new ControlAdmin();created.setAccount(account);created.setPasswordHash(passwords.encode(input.newPassword));created.setMfaSecret(secret);created.setMfaEnabled(true);admins.saveAndFlush(created);
  audit.record(actor.getId(),null,null,"CONTROL_ACCOUNT_CREATE",String.valueOf(created.getId()),"SUCCESS","MFA enabled",input.reason);return created;
 }
 @Transactional public ControlAdmin update(Long id,Input input){
  // Lock in ID order to prevent A resetting B concurrent with B resetting A deadlocks.
  Long actorId=ControlIdentity.actorId();ControlAdmin first=admins.lock(Math.min(actorId,id)).orElseThrow(ControlService::invalid);
  ControlAdmin second=actorId.equals(id)?first:admins.lock(Math.max(actorId,id)).orElseThrow(ControlService::invalid);
  ControlAdmin actor=first.getId().equals(actorId)?first:second;verify(actor,input);
  ControlAdmin target=first.getId().equals(id)?first:second;
  if(Boolean.FALSE.equals(input.enabled)&&actorId.equals(id))throw new IllegalArgumentException("不能停用当前总控账号");
  if(input.enabled==null&&input.newPassword==null)throw new IllegalArgumentException("请选择账号变更");
  if(input.enabled!=null)target.setEnabled(input.enabled);
  if(input.newPassword!=null){validatePassword(input.newPassword);target.setPasswordHash(passwords.encode(input.newPassword));}
  target.setSessionVersion(target.getSessionVersion()+1);
  audit.record(actorId,null,null,"CONTROL_ACCOUNT_UPDATE",String.valueOf(id),"SUCCESS","enabled="+target.isEnabled()+";credentialsChanged="+(input.newPassword!=null),input.reason);return target;
 }
 private ControlAdmin reauth(Input input){ControlAdmin actor=admins.lock(ControlIdentity.actorId()).orElseThrow(ControlService::invalid);verify(actor,input);return actor;}
 private void verify(ControlAdmin actor,Input input){if(input.reason==null||input.reason.trim().isEmpty())input.reason="总控账号管理";TenantManagementService.reason(input.reason);if(!actor.isEnabled()||input.password==null||!passwords.matches(input.password,actor.getPasswordHash())||!actor.isMfaEnabled()||!mfa.verify(actor.getMfaSecret(),input.totp))throw ControlService.invalid();}
 private static void validatePassword(String value){if(value==null||value.length()<6||value.length()>128)throw new IllegalArgumentException("总控密码长度须为 6 至 128 位，允许纯数字");}
 public static class Input {public String account,password,totp,newPassword,newSecret,newTotp,reason;public Boolean enabled;}
}
