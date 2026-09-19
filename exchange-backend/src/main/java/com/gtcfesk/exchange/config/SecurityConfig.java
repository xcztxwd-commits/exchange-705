package com.gtcfesk.exchange.config;

import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public FilterRegistrationBean<JwtFilter> jwtRegistration(JwtFilter filter) {
        FilterRegistrationBean<JwtFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwt) throws Exception {
        http.csrf().disable().cors().and()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
            .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling()
            .authenticationEntryPoint((req, res, e) -> error(res, 401, "请先登录"))
            .accessDeniedHandler((req, res, e) -> error(res, 403, "无权访问")).and()
            .authorizeRequests()
            .antMatchers("/api/auth/login", "/api/auth/register", "/api/auth/sendEmailCode", "/api/auth/resetPassword", "/api/admin/auth/login").permitAll()
            .antMatchers("/api/uploads/images/test/**").hasRole("SUPER_ADMIN")
            .antMatchers("/api/market/**", "/api/ws/market", "/uploads/**", "/api/uploads/**", "/api/user/announcements/**").permitAll()
            .antMatchers(org.springframework.http.HttpMethod.GET, "/api/user/system/timezone", "/api/user/customer-service/link", "/api/user/complaint/email", "/api/financial/products", "/api/financial/product/*", "/api/loan/settings", "/api/trade/option/symbols", "/api/trade/option/durations").permitAll()
            .antMatchers("/api/admin/**").hasAnyRole("SUPER_ADMIN", "ADMIN", "AGENT")
            .antMatchers("/api/upload/**").authenticated()
            .antMatchers("/api/**").hasRole("USER")
            .anyRequest().denyAll();
        return http.build();
    }
    private static void error(javax.servlet.http.HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
