package com.gtcfesk.exchange.control;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.access.AccessDeniedException;
import javax.servlet.*;import javax.servlet.http.*;import java.io.IOException;
/** Host/context boundary before Spring Security; no client tenant header is an authority. */
@Component @Order(-110) @RequiredArgsConstructor
public class TenantRequestFilter extends OncePerRequestFilter {
 private final TenantHostService hosts;
 @org.springframework.beans.factory.annotation.Autowired private TenantDomainVerification domains;
 public static boolean backendSharedPath(String path){return path.equals("/api/market/currencies")||path.equals("/api/upload/image")||path.equals("/api/upload/audio")||path.equals("/api/user/support/config")||path.startsWith("/api/uploads/images/")||path.startsWith("/api/uploads/audio/")||path.equals("/api/user/support/tones/arrival.wav")||path.equals("/api/user/support/tones/reply.wav");}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
  TenantContext.clear();String path=request.getRequestURI();
  try{
   // Server-local forward proof is private, created only after inspection-key, explicit tenant existence and fixed read-route validation. A header cannot create it.
   Long inspected=com.gtcfesk.exchange.simulation.SimulationAdminQueryBoundary.internalTenant(request);
   if(inspected!=null){if(!path.startsWith("/api/admin/"))throw new AccessDeniedException("模拟监管路径无效");try(TenantContext.Scope ignored=TenantContext.open(inspected)){chain.doFilter(request,response);}return;}
   if("/healthz".equals(path)){response.setStatus(200);response.getWriter().write("ok");return;}
   if(path.startsWith("/api/control/")){hosts.requireOrigin(request,hosts.controlOrigin());chain.doFilter(request,response);return;}
   if(path.startsWith("/api/admin/")||(hosts.isAdminHost(request)&&backendSharedPath(path))){hosts.requireOrigin(request,hosts.adminOrigin());chain.doFilter(request,response);return;}
   // Public files and user endpoints still require a recognized tenant host.
   if("/api/tenant-routing-check".equals(path)&&request.getParameter("challenge")!=null){
    if(!"GET".equals(request.getMethod()))throw new AccessDeniedException("挑战仅支持GET");
    Object reply=domains.routing(hosts.host(request),request.getParameter("challenge"));response.setHeader("Cache-Control","no-store");response.setContentType("application/json");response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(reply));return;
   }
   Tenant tenant=hosts.resolve(request);
   if("/api/tenant-routing-check".equals(path)){response.setContentType("application/json");response.getWriter().write("{\"tenantId\":"+tenant.getId()+"}");return;}
   if(!tenant.isDomainVerified()||"DISABLED".equals(tenant.getStatus()))throw new AccessDeniedException("租户未开放");
   String origin=request.getHeader("Origin");if(origin!=null&&!origin.equals(hosts.frontendOrigin(tenant.getFrontendHost())))throw new AccessDeniedException("来源不匹配");
   try(TenantContext.Scope ignored=TenantContext.open(tenant.getId())){chain.doFilter(request,response);}
  }catch(AccessDeniedException e){if(!response.isCommitted()){response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"success\":false,\"code\":\"TENANT_BOUNDARY_REJECTED\",\"message\":\"入口或租户上下文无效\"}");}}
  finally{TenantContext.clear();}
 }
}
