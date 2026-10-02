package com.gtcfesk.exchange.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.*;
import javax.servlet.http.*;
import java.nio.file.*;
import java.util.*;

/** Test-only live HTTP transport. Synthetic authentication seam; production controllers and SQL are real. */
@TestComponent
public class UnifiedInboxBrowserFixture {
    static UnifiedInboxTest seed;
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude={SecurityAutoConfiguration.class,UserDetailsServiceAutoConfiguration.class})
    @Import({UnifiedInboxFixture.class,Errors.class})
    public static class Web {
        @Bean FilterRegistrationBean<OncePerRequestFilter> fixtureIdentity(){
            FilterRegistrationBean<OncePerRequestFilter> bean=new FilterRegistrationBean<>();
            bean.setFilter(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws java.io.IOException,ServletException {
                    if(!request.getRequestURI().startsWith("/api/")){chain.doFilter(request,response);return;}
                    if(seed==null){response.sendError(503);return;}
                    String token=request.getHeader("Authorization"); boolean admin="Bearer t04-admin".equals(token);
                    Long user="Bearer t04-user-a".equals(token)?Long.valueOf(seed.a):"Bearer t04-user-b".equals(token)?Long.valueOf(seed.b):"Bearer t04-user-c".equals(token)?Long.valueOf(seed.c):null;
                    boolean publicRead=request.getMethod().equals("GET")&&request.getRequestURI().startsWith("/api/user/announcements");
                    if(!admin&&user==null&&!publicRead){response.sendError(401);return;}
                    if(request.getRequestURI().startsWith("/api/admin/")&&!admin){response.sendError(403);return;}
                    long tenant=Objects.equals(user,seed.c)?seed.otherTenant:seed.tenant;
                    try(TenantContext.Scope ignored=TenantContext.open(tenant)){
                        if(admin)SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1",null,Collections.singleton(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                        else if(user!=null)UnifiedInboxTest.auth(user);
                        chain.doFilter(request,response);
                    } finally{SecurityContextHolder.clearContext();}
                }
            });bean.setOrder(-100);return bean;
        }
    }
    @RestControllerAdvice public static class Errors {
        @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class) @ResponseStatus(org.springframework.http.HttpStatus.FORBIDDEN)
        Map<String,Object> denied(Exception e){return Collections.singletonMap("message",e.getMessage());}
    }
    public static void main(String[] args)throws Exception {
        if(!"true".equals(System.getProperty("t04.browser")))throw new IllegalArgumentException("Explicit isolated browser fixture opt-in required");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication app=new SpringApplication(Web.class);Map<String,Object> props=new HashMap<>();
        props.put("server.port",18404);props.put("server.address","127.0.0.1");props.put("spring.jmx.enabled",false);
        props.put("spring.main.banner-mode","off");props.put("logging.level.root","WARN");
        app.setDefaultProperties(props);
        ConfigurableApplicationContext context=app.run("--server.port=18404","--server.address=127.0.0.1");
        UnifiedInboxTest data=new UnifiedInboxTest();context.getAutowireCapableBeanFactory().autowireBean(data);data.seed();
        data.in(data.tenant,()->new TransactionTemplate(data.manager).execute(s->{for(int i=0;i<32;i++)data.letter(data.a,"Message "+(i+1),data.epoch.minusMinutes(i));return null;}));
        seed=data;
        Map<String,Object> identity=new LinkedHashMap<>();identity.put("tenantId",data.tenant);identity.put("otherTenantId",data.otherTenant);identity.put("userA",data.a);identity.put("userB",data.b);identity.put("userC",data.c);identity.put("announcement",data.announcement);identity.put("letter",data.letter);identity.put("activity",data.activity);identity.put("draft",data.draft);
        Path file=Paths.get(System.getProperty("t04.identity"));Files.write(file,new ObjectMapper().writeValueAsBytes(identity));
        System.out.println("T04_LIVE_FIXTURE_READY "+file);
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
