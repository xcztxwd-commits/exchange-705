package com.gtcfesk.exchange.control;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import javax.servlet.http.HttpServletRequest;
import java.net.*;
import java.util.*;
@Service
public class TenantHostService {
 private final TenantRepository tenants;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.security.OutboundEndpointPolicy routing;
 private final List<IpAddressMatcher> proxies=new ArrayList<>();
 private final String baseDomain,adminOrigin,controlOrigin;
 @Value("${platform.entry-base-domain:forex-exchange.net}") private String entryBaseDomain="forex-exchange.net";
 private static final Set<String> RESERVED=new HashSet<>(Arrays.asList("www","admin","admin-panel","control","control-panel","api","mail","ns1","ns2","status"));
 public TenantHostService(TenantRepository tenants,@Value("${platform.base-domain:forex-exchange.cc}") String baseDomain,
  @Value("${platform.admin-origin:}") String adminOrigin,@Value("${platform.control-origin:}") String controlOrigin,
  @Value("${security.trusted-proxies:}") String trusted){
  this.tenants=tenants;this.baseDomain=baseDomain.isEmpty()?"":normalizeHost(baseDomain);this.adminOrigin=normalizeOrigin(adminOrigin);this.controlOrigin=normalizeOrigin(controlOrigin);
  for(String p:trusted.split(","))if(!p.trim().isEmpty())proxies.add(new IpAddressMatcher(p.trim()));
 }
 public static String normalizeHost(String raw){
  if(raw==null||raw.length()>260||raw.matches(".*[\\s/@#?,\\\\].*"))throw new AccessDeniedException("无效域名");
  try{URI uri=new URI("https://"+raw);String host=uri.getHost();if(host==null||host.endsWith(".")||uri.getRawUserInfo()!=null||!uri.getRawPath().isEmpty()||uri.getPort()==0||uri.getPort() < -1||uri.getPort()>65535)throw new Exception();
   return IDN.toASCII(host).toLowerCase(Locale.ROOT);
  }catch(Exception e){throw new AccessDeniedException("无效域名");}
 }
 public static String normalizeOrigin(String raw){
  if(raw==null||raw.trim().isEmpty())return "";
  try{URI u=new URI(raw);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||!(u.getPath().isEmpty()||"/".equals(u.getPath())))throw new Exception();
   return "https://"+normalizeHost(u.getHost())+(u.getPort()==-1||u.getPort()==443?"":":"+u.getPort());
  }catch(Exception e){throw new IllegalArgumentException("平台入口必须为精确 HTTPS origin");}
 }
 public boolean trustedProxy(HttpServletRequest request){return proxies.stream().anyMatch(p->p.matches(request.getRemoteAddr()));}
 public String host(HttpServletRequest request){
  if(Collections.list(request.getHeaders("Host")).size()!=1||Collections.list(request.getHeaders("X-Forwarded-Host")).size()>1||Collections.list(request.getHeaders("X-Edge-TLS-Server-Name")).size()>1)throw new AccessDeniedException("Host必须唯一");
  String direct=normalizeHost(request.getHeader("Host")),forwarded=request.getHeader("X-Forwarded-Host");
  if(forwarded!=null){if(!trustedProxy(request)||!direct.equals(normalizeHost(forwarded)))throw new AccessDeniedException("转发Host不可信或不一致");}
  String sni=request.getHeader("X-Edge-TLS-Server-Name");if(sni!=null&&!sni.isEmpty()&&(!trustedProxy(request)||!direct.equals(normalizeHost(sni))))throw new AccessDeniedException("TLS SNI与真实Host不一致");
  return direct;
 }
 public boolean isEntryHost(String host){return host.endsWith("."+normalizeHost(entryBaseDomain));}
 public void requireOrigin(HttpServletRequest request,String expected){
  if(expected.isEmpty()||!normalizeHost(URI.create(expected).getAuthority()).equals(host(request)))throw new AccessDeniedException("入口不匹配");
  String origin=request.getHeader("Origin");if(origin!=null&&!expected.equals(origin))throw new AccessDeniedException("来源不匹配");
 }
 public boolean isAdminHost(HttpServletRequest request){return !adminOrigin.isEmpty()&&normalizeHost(URI.create(adminOrigin).getAuthority()).equals(host(request));}
 public boolean isControlHost(HttpServletRequest request){return !controlOrigin.isEmpty()&&normalizeHost(URI.create(controlOrigin).getAuthority()).equals(host(request));}
 public Tenant resolve(HttpServletRequest request){return tenants.findByFrontendHost(host(request)).orElseThrow(()->new AccessDeniedException("未知租户域名"));}
 public String validateAssignment(String host){return validateAssignment(host,"FRONTEND");}
 public String validateAssignment(String host,String role){
  if(!Arrays.asList("ENTRY","FRONTEND").contains(role))throw new IllegalArgumentException("域名角色无效");
  String normalized=normalizeHost(host),root="ENTRY".equals(role)?normalizeHost(entryBaseDomain):baseDomain;
  if(host.indexOf(':')>=0||root.isEmpty()||!normalized.endsWith("."+root))throw new IllegalArgumentException("域名不在该角色的平台基础域下");
  String label=normalized.substring(0,normalized.length()-root.length()-1);
  if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")||RESERVED.contains(label)||normalized.equals(originHost(adminOrigin))||normalized.equals(originHost(controlOrigin)))throw new IllegalArgumentException("仅支持非保留的单层租户子域名");
  return normalized;
 }
 private String originHost(String origin){return origin.isEmpty()?"":URI.create(origin).getHost();}
 public String frontendOrigin(String host){String normalized=normalizeHost(host);return routing==null?"https://"+normalized:routing.routingOrigin(normalized);}
 public String adminOrigin(){return adminOrigin;}
 public String controlOrigin(){return controlOrigin;}
}
