package com.gtcfesk.exchange.control;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.access.AccessDeniedException;
import javax.servlet.*;import javax.servlet.http.*;import java.io.IOException;
import java.util.*;
/** Host/context boundary before Spring Security and before the gateway's static fallback. */
@Component @Order(-110) @RequiredArgsConstructor
public class TenantRequestFilter extends OncePerRequestFilter {
 private final TenantHostService hosts;
 @org.springframework.beans.factory.annotation.Autowired private TenantDomainVerification domains;
 public static boolean backendSharedPath(String path){return path.equals("/api/market/currencies")||path.equals("/api/upload/image")||path.equals("/api/upload/audio")||path.equals("/api/user/support/config")||path.startsWith("/api/uploads/images/")||path.startsWith("/api/uploads/audio/")||path.equals("/api/user/support/tones/arrival.wav")||path.equals("/api/user/support/tones/reply.wav");}
 private static final Set<String> PC_PAGES=new HashSet<>(Arrays.asList("/","/demo","/trade","/login","/register","/forgot-password","/language","/customer-service","/inbox"));
 private static final Set<String> MOBILE_PAGES=new HashSet<>(Arrays.asList("/","/home","/demo","/trade","/orders","/profile","/explore","/assets","/deposit","/deposit/records","/wallet","/wallet/bind-bank-card","/wallet/bind-digital-currency","/verification","/transfer","/change-password","/customer-service","/inbox","/complaint","/announcements","/withdraw","/credit-loan","/loan/personal-info","/loan/apply-info","/loan/contract","/loan/sign","/loan/records","/financial-management","/financial/purchase","/financial/orders","/financial/yield-list","/search","/invite","/login","/register","/forgot-password","/language"));
 private static final Set<String> QUERY=new HashSet<>(Arrays.asList("tab","symbol","category","lang","invite","invitationCode","id","orderId","activity","edition","panel","status","login","register","forgot"));
 private static final Set<String> REDIRECT_QUERY=new HashSet<>(Arrays.asList("next","url","redirect","redirect_uri","returnTo","callback","target"));
 private static String navigation(HttpServletRequest r){
  String method=r.getMethod(),path=r.getRequestURI(),mode=r.getHeader("Sec-Fetch-Mode"),dest=r.getHeader("Sec-Fetch-Dest"),accept=r.getHeader("Accept");
  if(!Arrays.asList("GET","HEAD").contains(method)||r.getHeader("Upgrade")!=null||accept==null||!accept.toLowerCase(Locale.ROOT).contains("text/html")||mode!=null&&!"navigate".equals(mode)||dest!=null&&!"document".equals(dest))throw new AccessDeniedException("非页面导航");
  boolean allowed=PC_PAGES.contains(path)||path.equals("/mobile")||path.startsWith("/mobile/")&&MOBILE_PAGES.contains(path.substring(7));
  if(!allowed||r.getQueryString()!=null&&r.getQueryString().length()>2048||r.getParameterMap().size()>20)throw new AccessDeniedException("路径不可用");
  List<String> query=new ArrayList<>();
  for(Map.Entry<String,String[]> p:r.getParameterMap().entrySet()){
   String key=p.getKey();if(REDIRECT_QUERY.contains(key))throw new AccessDeniedException("不接受跳转目标");if(!QUERY.contains(key))continue;
   String[] values=p.getValue();if(values.length!=1||!values[0].matches("[A-Za-z0-9_-]{1,64}"))throw new AccessDeniedException("参数不可用");
   if(Arrays.asList("id","orderId","activity").contains(key)&&!values[0].matches("[0-9]{1,20}"))throw new AccessDeniedException("参数不可用");
   query.add(key+"="+values[0]); // Whitelisted ASCII only; no credential, URL or delimiter can survive.
  }
  return ("/mobile".equals(path)?"/mobile/":path)+(query.isEmpty()?"":"?"+String.join("&",query));
 }
 private static void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");}
 private static void unavailable(HttpServletResponse response)throws IOException{noStore(response);response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"success\":false,\"code\":\"TENANT_BOUNDARY_REJECTED\",\"message\":\"入口或租户上下文无效\"}");}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
  TenantContext.clear();String path=request.getRequestURI();
  try{
   String host=hosts.host(request);
   if("/api/tenant-routing-check".equals(path)&&request.getParameter("challenge")!=null){
    noStore(response);if(!"GET".equals(request.getMethod())||request.getParameterValues("challenge").length!=1)throw new AccessDeniedException("挑战仅支持单nonce GET");
    Object reply=domains.routing(host,request.getParameter("challenge"));response.setContentType("application/json");response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(reply));return;
   }
   // Only the internal gateway subrequest may classify a host. It carries no redirect address or user identity.
   if("/internal/tenant-gateway".equals(path)){
    noStore(response);if(!hosts.trustedProxy(request)||!"1".equals(request.getHeader("X-Tenant-Gateway"))||!"GET".equals(request.getMethod()))throw new AccessDeniedException("非网关请求");
    if(hosts.isEntryHost(host)){response.setStatus(401);return;}
    if(hosts.isAdminHost(request)||hosts.isControlHost(request)){response.setStatus(204);return;}
    Tenant t=hosts.resolve(request);if(!t.isDomainVerified()||"DISABLED".equals(t.getStatus()))throw new AccessDeniedException("租户未开放");response.setStatus(204);return;
   }
   if(hosts.isEntryHost(host)){
    noStore(response);try{String suffix=navigation(request),target=domains.entryTarget(host);response.setStatus(302);response.setHeader("Location",hosts.frontendOrigin(target)+suffix);}catch(RuntimeException e){unavailable(response);}return;
   }
   // Server-local inspection proof cannot be created by client headers.
   Long inspected=com.gtcfesk.exchange.simulation.SimulationAdminQueryBoundary.internalTenant(request);
   if(inspected!=null){if(!path.startsWith("/api/admin/"))throw new AccessDeniedException("模拟监管路径无效");try(TenantContext.Scope ignored=TenantContext.open(inspected)){chain.doFilter(request,response);}return;}
   if("/healthz".equals(path)){response.setStatus(200);response.getWriter().write("ok");return;}
   if(path.startsWith("/api/control/")){hosts.requireOrigin(request,hosts.controlOrigin());chain.doFilter(request,response);return;}
   if(path.startsWith("/api/admin/")||(hosts.isAdminHost(request)&&backendSharedPath(path))){hosts.requireOrigin(request,hosts.adminOrigin());chain.doFilter(request,response);return;}
   Tenant tenant=hosts.resolve(request);
   if("/api/tenant-routing-check".equals(path)){noStore(response);if(!"GET".equals(request.getMethod()))throw new AccessDeniedException("只读路由检查");response.setContentType("application/json");response.getWriter().write("{\"tenantId\":"+tenant.getId()+"}");return;}
   if(!tenant.isDomainVerified()||"DISABLED".equals(tenant.getStatus()))throw new AccessDeniedException("租户未开放");
   String origin=request.getHeader("Origin");if(origin!=null&&!origin.equals(hosts.frontendOrigin(tenant.getFrontendHost())))throw new AccessDeniedException("来源不匹配");
   try(TenantContext.Scope ignored=TenantContext.open(tenant.getId())){chain.doFilter(request,response);}
  }catch(AccessDeniedException e){if(!response.isCommitted())unavailable(response);}
  finally{TenantContext.clear();}
 }
}
