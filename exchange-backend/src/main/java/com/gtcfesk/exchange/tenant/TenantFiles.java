package com.gtcfesk.exchange.tenant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.nio.file.*;
public final class TenantFiles {
 private TenantFiles(){}
 public static String ownerPath(){
  Authentication a=SecurityContextHolder.getContext().getAuthentication();
  if(a==null||!a.isAuthenticated())throw new AccessDeniedException("请先登录");
  boolean user=a.getAuthorities().stream().anyMatch(r->"ROLE_USER".equals(r.getAuthority()));
  String subject=a.getName();if(!subject.matches("(?:agent-)?-?[0-9]{1,19}"))throw new AccessDeniedException("身份无效");
  return TenantContext.requireTenantId()+"/"+(user?"user":"staff")+"/"+subject;
 }
 public static Path directory(Path base)throws java.io.IOException {Path result=base.resolve(ownerPath()).normalize();if(!result.startsWith(base.normalize()))throw new AccessDeniedException("无权访问");Files.createDirectories(result);return result;}
}
