package com.gtcfesk.exchange.control;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service @RequiredArgsConstructor
public class BackendAccountService {
 private final BackendLoginRepository logins;private final BackendLoginRegistry registry;private final AdminUserRepository admins;private final PasswordEncoder passwords;private final ControlAuditService audit;private final AdminUserIdentity userIdentity;
 @Transactional(readOnly=true) public List<Map<String,Object>> list(Long tenant){return list(tenant,null);}
 @Transactional(readOnly=true) public List<Map<String,Object>> list(Long tenant,String userEmail){
  AdminUserIdentity.emailPattern(userEmail);String email=userEmail==null?"":userEmail.trim().toLowerCase(Locale.ROOT);
  try(TenantContext.Scope scope=TenantContext.open(tenant)){
   List<Map<String,Object>> rows=userIdentity.rows(logins.findByTenantId(TenantContext.requireTenantId()));
   for(Map<String,Object> row:rows)row.put("subjectId","ADMIN".equals(row.get("subjectType"))?row.get("adminUserId"):row.get("userId"));
   return rows.stream().filter(row->email.isEmpty()||Objects.toString(row.get("userEmail"),"").toLowerCase(Locale.ROOT).contains(email)).collect(java.util.stream.Collectors.toList());
  }
 }
 @Transactional public BackendLogin create(Long tenant,Input input){
  TenantManagementService.reason(input.reason);
  try(TenantContext.Scope scope=TenantContext.open(tenant)){
   BackendLogin result;
   if("AGENT".equals(input.type)){if(input.subjectId==null)throw new IllegalArgumentException("请选择本租户已有代理");result=registry.register("AGENT",input.subjectId,input.account);}
   else if("ADMIN".equals(input.type)){
    if(input.password==null||input.password.length()<6||input.password.length()>128||input.email==null||!input.email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))throw new IllegalArgumentException("邮箱或密码无效（密码须为 6 至 128 位，允许纯数字）");
    if(input.role!=null&&!Arrays.asList("admin","super_admin").contains(input.role))throw new IllegalArgumentException("通过现有角色页面分配员工角色");
    registry.requireAvailable(input.account);
    AdminUser a=new AdminUser();a.setAccount(BackendLoginRegistry.normalize(input.account));a.setEmail(input.email.trim().toLowerCase(Locale.ROOT));a.setPasswordHash(passwords.encode(input.password));a.setRole(input.role==null?"admin":input.role);a.setEnabled(true);a.setMustChangePassword(true);try{admins.saveAndFlush(a);}catch(org.springframework.dao.DataIntegrityViolationException conflict){throw new IllegalArgumentException("账号或邮箱不可用");}result=registry.register("ADMIN",a.getId(),a.getAccount());
   }else throw new IllegalArgumentException("账号类型无效");
   ControlIdentity actor=ControlIdentity.current();if(actor!=null)audit.record(actor.getActorId(),tenant,actor.getAccessSessionId(),"BACKEND_ACCOUNT_CREATE",result.getSubjectType()+":"+result.subjectId(),"SUCCESS","account="+result.getNormalizedAccount(),input.reason);return result;
  }
 }
 public static class Input{public String type,account,email,password,role,reason;public Long subjectId;}
}
