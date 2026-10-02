package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import javax.servlet.*;import javax.servlet.http.*;import java.io.IOException;import java.util.*;
/** Shared, bounded login/MFA limits. Redis failure fails closed, never disables MFA protection. */
@Component @Order(-105) @RequiredArgsConstructor
public class ControlLoginRateLimit extends OncePerRequestFilter {
 private final StringRedisTemplate redis;private final ControlAuditService audit;
 private static final DefaultRedisScript<Long> LIMIT=new DefaultRedisScript<>("local a=redis.call('INCR',KEYS[1]);if a==1 then redis.call('EXPIRE',KEYS[1],60) end;local b=redis.call('INCR',KEYS[2]);if b==1 then redis.call('EXPIRE',KEYS[2],60) end;if a>20 or b>300 then return 0 end;return 1",Long.class);
 @Override protected boolean shouldNotFilter(HttpServletRequest r){return !("POST".equals(r.getMethod())||"PUT".equals(r.getMethod()))||!(Arrays.asList("/api/control/auth/login","/api/admin/auth/login","/api/control/security/mfa").contains(r.getRequestURI())||r.getRequestURI().startsWith("/api/control/accounts"));}
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws IOException,ServletException{
  String group=r.getRequestURI().startsWith("/api/control/")?"control":"backend";s.setHeader("Cache-Control","no-store");
  try{Long accepted=redis.execute(LIMIT,Arrays.asList("security:{"+group+"}:login:ip:"+r.getRemoteAddr(),"security:{"+group+"}:login:global"));if(!Long.valueOf(1).equals(accepted)){s.setStatus(429);s.setHeader("Retry-After","60");return;}}
  catch(Exception e){s.setStatus(503);return;}
  try{chain.doFilter(r,s);}finally{if(s.getStatus()>=400&&"control".equals(group))audit.failure(null,null,null,"CONTROL_AUTH_FAILURE",r.getRequestURI());}
 }
}
