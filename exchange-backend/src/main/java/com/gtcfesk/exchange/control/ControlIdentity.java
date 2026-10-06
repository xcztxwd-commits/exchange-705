package com.gtcfesk.exchange.control;
import lombok.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
@Value
public class ControlIdentity {
 Long actorId; Long tenantId; String accessSessionId;
 public static ControlIdentity current(){Authentication a=SecurityContextHolder.getContext().getAuthentication();return a!=null&&a.getDetails() instanceof ControlIdentity?(ControlIdentity)a.getDetails():null;}
 /** Platform-wide reads require an authenticated control actor, never an access ticket or tenant context. */
 public static Long requireIndependent(){
  Authentication a=SecurityContextHolder.getContext().getAuthentication();ControlIdentity i=current();
  if(a==null||!a.isAuthenticated()||a.getAuthorities().stream().noneMatch(x->"ROLE_CONTROL".equals(x.getAuthority()))||i==null||i.actorId==null||i.actorId<=0||i.tenantId!=null||i.accessSessionId!=null||com.gtcfesk.exchange.tenant.TenantContext.currentTenantId()!=null)throw new org.springframework.security.access.AccessDeniedException("需要无租户上下文的独立总控身份");
  return i.actorId;
 }
 public static boolean isAccess(){return current()!=null&&current().accessSessionId!=null;}
 public static Long actorId(){ControlIdentity i=current();if(i==null)throw new org.springframework.security.access.AccessDeniedException("总控身份无效");return i.actorId;}
}
