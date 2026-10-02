package com.gtcfesk.exchange.config;
import org.springframework.context.annotation.*;
import org.springframework.web.cors.*;
import org.springframework.web.filter.CorsFilter;
@Configuration
public class CorsConfig {
 @Bean public CorsFilter corsFilter(com.gtcfesk.exchange.control.TenantHostService hosts){
  // TLS ends at the gateway. Use the verified public origin, not the backend HTTP scheme.
  return new CorsFilter(request->{
   CorsConfiguration c=new CorsConfiguration();c.setAllowCredentials(false);
   c.setAllowedMethods(java.util.Arrays.asList("GET","POST","PUT","DELETE","PATCH","OPTIONS"));
   c.setAllowedHeaders(java.util.Arrays.asList("Authorization","Content-Type"));c.setMaxAge(300L);
   String origin=request.getHeader("Origin");
   if(origin!=null)try{
    if(hosts.isControlHost(request)){hosts.requireOrigin(request,hosts.controlOrigin());c.addAllowedOrigin(hosts.controlOrigin());}
    else if(hosts.isAdminHost(request)){hosts.requireOrigin(request,hosts.adminOrigin());c.addAllowedOrigin(hosts.adminOrigin());}
    else{com.gtcfesk.exchange.control.Tenant t=hosts.resolve(request);String expected=hosts.frontendOrigin(t.getFrontendHost());if(t.isDomainVerified()&&!"DISABLED".equals(t.getStatus())&&expected.equals(origin))c.addAllowedOrigin(expected);}
   }catch(org.springframework.security.access.AccessDeniedException ignored){/* No permitted origin: standard CORS rejection. */}
   return c;
  });
 }
}
