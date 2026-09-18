package com.gtcfesk.exchange.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .cors().and()
                .authorizeRequests()
                // 开发阶段全部放行，避免 403；后续接入真实 JWT 时再按模块收紧
                .antMatchers("/uploads/**").permitAll() // 优先允许访问上传的静态资源
                .antMatchers("/api/**").permitAll()
                .anyRequest().permitAll();

        return http.build();
    }
}

