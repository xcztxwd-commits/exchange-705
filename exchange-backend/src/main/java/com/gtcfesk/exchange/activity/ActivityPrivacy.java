package com.gtcfesk.exchange.activity;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import javax.servlet.http.*;
@Configuration
public class ActivityPrivacy implements WebMvcConfigurer,HandlerInterceptor {
 public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).addPathPatterns("/api/activity/**","/api/admin/activities/**","/api/admin/activities");}
 public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){response.setHeader("Cache-Control","no-store");return true;}
}
