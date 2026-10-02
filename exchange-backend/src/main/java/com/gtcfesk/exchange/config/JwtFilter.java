package com.gtcfesk.exchange.config;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;import javax.servlet.http.*;import java.io.IOException;import java.util.*;
@Component @RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {
 private final JwtUtil jwtUtil;private final UserAccountRepository userAccountRepository;private final AdminUserRepository adminUserRepository;
 @org.springframework.beans.factory.annotation.Autowired private ControlService control;
 @org.springframework.beans.factory.annotation.Autowired private TenantPolicyService policy;
 @org.springframework.beans.factory.annotation.Autowired private BackendLoginRegistry logins;
 @org.springframework.beans.factory.annotation.Autowired private ControlAuditService audit;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.user.UserActivityService activity;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.simulation.SimulationEnvironment simulation;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.simulation.SimulationGateway simulationGateway;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.simulation.SimulationProvisioner simulationProvisioner;
 @Override protected boolean shouldNotFilter(HttpServletRequest r){return Arrays.asList("/api/auth/login","/api/auth/captcha","/api/auth/register","/api/auth/sendEmailCode","/api/auth/resetPassword","/api/admin/auth/login","/api/admin/auth/control-exchange","/api/control/auth/login").contains(r.getRequestURI());}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  String header=request.getHeader("Authorization");TenantContext.Scope scope=null;Long activeUser=null;ControlIdentity identity=null;Long failedActor=null,failedTenant=null;boolean controlAttempt=false;
  try{
   if(header!=null&&header.startsWith("Bearer ")){
    if(simulation!=null&&simulation.enabled()){
     Long tenant=TenantContext.requireTenantId();policy.requireLogin(tenant);
     Long id=simulationGateway.authenticate(header);simulationProvisioner.catalog(header);simulationProvisioner.user(id);
     authenticate(id.toString(),null,"USER");
    }else{
    Claims claims=jwtUtil.parse(header.substring(7));String type=claims.get("userType",String.class);String path=request.getRequestURI();
    controlAttempt="control".equals(type)||"control_access".equals(type);if(controlAttempt){if(claims.get("tenantId") instanceof Number)failedTenant=((Number)claims.get("tenantId")).longValue();String subject=claims.getSubject();String prefix="control_access".equals(type)?"control_access-":"control-";if(subject!=null&&subject.startsWith(prefix))try{failedActor=Long.valueOf(subject.substring(prefix.length()));}catch(NumberFormatException ignored){}}
    if(claims.getExpiration()==null||claims.getSubject()==null)throw new IllegalArgumentException();
    if("control".equals(type)){
     if(!path.startsWith("/api/control/"))throw new IllegalArgumentException();ControlAdmin actor=control.validateControl(claims);identity=new ControlIdentity(actor.getId(),null,null);authenticate(actor.getId().toString(),identity,"CONTROL");
    }else if("control_access".equals(type)){
     if((!path.startsWith("/api/admin/")&&!TenantRequestFilter.backendSharedPath(path))||TenantContext.currentTenantId()!=null)throw new IllegalArgumentException();identity=control.validateAccess(claims);scope=TenantContext.open(identity.getTenantId());authenticate(String.valueOf(-identity.getActorId()),identity,"SUPER_ADMIN","CONTROL_ACCESS");
    }else{
     if(!(claims.get("tenantId") instanceof Number))throw new IllegalArgumentException();Long tenant=((Number)claims.get("tenantId")).longValue();
     if(!claims.getSubject().startsWith(type+"-"))throw new IllegalArgumentException();Long id=Long.valueOf(claims.getSubject().substring(type.length()+1));
     boolean backend="admin".equals(type)||"agent".equals(type);
     // Backend shared routes are valid only on the shared admin entry, never on a frontend tenant Host.
     if(backend&&((!path.startsWith("/api/admin/")&&!TenantRequestFilter.backendSharedPath(path))||TenantContext.currentTenantId()!=null))throw new IllegalArgumentException();
     if(!backend&&(TenantContext.currentTenantId()==null||!TenantContext.requireTenantId().equals(tenant)))throw new IllegalArgumentException();
     scope=TenantContext.open(tenant);policy.requireLogin(tenant);
     if(!(claims.get("tenantVersion") instanceof Number)||((Number)claims.get("tenantVersion")).longValue()!=policy.current().getSessionVersion())throw new IllegalArgumentException();
     String principal,role,password;
     if("admin".equals(type)){
      AdminUser a=adminUserRepository.findByTenantIdAndId(tenant,id).orElseThrow(IllegalArgumentException::new);
      if(!Boolean.TRUE.equals(a.getEnabled())||a.getCurrentToken()==null||!a.getCurrentToken().equals(claims.get("sid"))||!logins.active("ADMIN",id))throw new IllegalArgumentException();if(a.isMustChangePassword()&&!path.equals("/api/admin/auth/profile/password"))throw new org.springframework.security.access.AccessDeniedException("首次登录必须修改密码");password=a.getPasswordHash();principal=id.toString();role="super_admin".equals(a.getRole())?"SUPER_ADMIN":"ADMIN";
     }else if("user".equals(type)||"agent".equals(type)){
      UserAccount u=userAccountRepository.findByTenantIdAndId(tenant,id).orElseThrow(IllegalArgumentException::new);
      if(!Arrays.asList("normal","active").contains(u.getStatus()==null?"":u.getStatus().toLowerCase(Locale.ROOT))||u.getCurrentToken()==null||!u.getCurrentToken().equals(claims.get("sid")))throw new IllegalArgumentException();
      if("agent".equals(type)&&(!"agent".equals(u.getUserType())||!logins.active("AGENT",id)))throw new IllegalArgumentException();password=u.getPasswordHash();principal="agent".equals(type)?"agent-"+id:id.toString();role="agent".equals(type)?"AGENT":"USER";if("user".equals(type))activeUser=id;
     }else throw new IllegalArgumentException();
     if(!jwtUtil.credentialKey(password).equals(claims.get("credential")))throw new IllegalArgumentException();authenticate(principal,null,role);
    }
    }
   }
  }catch(Exception e){if(scope!=null)scope.close();SecurityContextHolder.clearContext();if(controlAttempt)audit.failure(failedActor,failedTenant,null,"CONTROL_TOKEN_REJECTED",request.getMethod()+" "+request.getRequestURI());response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"success\":false,\"message\":\"登录已失效，请重新登录\",\"code\":\"TOKEN_INVALID\"}");return;}
  try{
   chain.doFilter(request,response);
   if(activeUser!=null&&response.getStatus()<400&&activity!=null)activity.touch(activeUser);
   if(identity!=null&&identity.getAccessSessionId()!=null)audit.record(identity.getActorId(),identity.getTenantId(),identity.getAccessSessionId(),"ACCESS_REQUEST",request.getMethod()+" "+request.getRequestURI(),response.getStatus()<400?"HTTP_SUCCESS":"HTTP_FAILED","HTTP status="+response.getStatus(),null);
  }catch(ServletException|RuntimeException e){if(identity!=null&&identity.getAccessSessionId()!=null)audit.failure(identity.getActorId(),identity.getTenantId(),identity.getAccessSessionId(),"ACCESS_REQUEST_FAILED",request.getMethod()+" "+request.getRequestURI());throw e;}finally{if(scope!=null)scope.close();}
 }
 private void authenticate(String principal,ControlIdentity identity,String...roles){List<SimpleGrantedAuthority> authorities=new ArrayList<>();for(String role:roles)authorities.add(new SimpleGrantedAuthority("ROLE_"+role));UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken(principal,null,authorities);auth.setDetails(identity);SecurityContextHolder.getContext().setAuthentication(auth);}
}
