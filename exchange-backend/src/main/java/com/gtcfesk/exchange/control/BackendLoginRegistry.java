package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.admin.AdminUserRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import java.util.*;
@Service @RequiredArgsConstructor
public class BackendLoginRegistry {
 @org.springframework.beans.factory.annotation.Autowired private TenantPolicyService policy;
 private final BackendLoginRepository logins;private final AdminUserRepository admins;private final UserAccountRepository users;
 public static String normalize(String input){if(input==null)throw new IllegalArgumentException("账号不可用");String value=input.trim().toLowerCase(Locale.ROOT);if(value.length()<3||value.length()>128||!value.matches("[a-z0-9_.@+-]+"))throw new IllegalArgumentException("账号不可用");return value;}
 /** Creation preflight uses the global namespace before any tenant subject is persisted. */
 public void requireAvailable(String account){TenantContext.requireTenantId();if(logins.findByNormalizedAccount(normalize(account)).isPresent())throw new IllegalArgumentException("账号不可用");}
 public BackendLogin resolve(String input){return logins.findByNormalizedAccount(normalize(input)).filter(BackendLogin::isEnabled).orElseThrow(()->new AccessDeniedException("账号或密码错误"));}
 @Transactional public BackendLogin register(String type,Long subject,String account){
  Long tenant=TenantContext.requireTenantId();String normalized=normalize(account);BackendLogin entry;
  if("ADMIN".equals(type)){admins.findByTenantIdAndId(tenant,subject).orElseThrow(()->new AccessDeniedException("主体不可用"));entry=logins.findByTenantIdAndAdminUserId(tenant,subject).orElseGet(BackendLogin::new);entry.setAdminUserId(subject);}
  else if("AGENT".equals(type)){if(!users.findByTenantIdAndId(tenant,subject).map(u->"agent".equals(u.getUserType())).orElse(false))throw new AccessDeniedException("主体不可用");entry=logins.findByTenantIdAndUserId(tenant,subject).orElseGet(BackendLogin::new);if(entry.getId()==null||!entry.isEnabled())policy.requireNewBusiness("agent");entry.setUserId(subject);}
  else throw new IllegalArgumentException("主体类型无效");
  BackendLogin taken=logins.findByNormalizedAccount(normalized).orElse(null);if(taken!=null&&!Objects.equals(taken.getId(),entry.getId()))throw new IllegalArgumentException("账号不可用");
  entry.setTenantId(tenant);entry.setSubjectType(type);entry.setNormalizedAccount(normalized);entry.setEnabled(true);
  try{return logins.saveAndFlush(entry);}catch(org.springframework.dao.DataIntegrityViolationException e){throw new IllegalArgumentException("账号不可用");}
 }
 @Transactional public void removeAdmin(Long id){logins.findByTenantIdAndAdminUserId(TenantContext.requireTenantId(),id).ifPresent(logins::delete);logins.flush();}
 public boolean active(String type,Long id){Long tenant=TenantContext.requireTenantId();return ("ADMIN".equals(type)?logins.findByTenantIdAndAdminUserId(tenant,id):logins.findByTenantIdAndUserId(tenant,id)).map(BackendLogin::isEnabled).orElse(false);}
}
