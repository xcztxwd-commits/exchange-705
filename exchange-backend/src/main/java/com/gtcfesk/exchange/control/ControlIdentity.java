package com.gtcfesk.exchange.control;
import lombok.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
@Value
public class ControlIdentity {
 Long actorId; Long tenantId; String accessSessionId;
 public static ControlIdentity current(){Authentication a=SecurityContextHolder.getContext().getAuthentication();return a!=null&&a.getDetails() instanceof ControlIdentity?(ControlIdentity)a.getDetails():null;}
 public static boolean isAccess(){return current()!=null&&current().accessSessionId!=null;}
 public static Long actorId(){ControlIdentity i=current();if(i==null)throw new org.springframework.security.access.AccessDeniedException("总控身份无效");return i.actorId;}
}
