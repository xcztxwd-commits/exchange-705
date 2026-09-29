package com.gtcfesk.exchange.config;

import com.gtcfesk.exchange.user.KycIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Gate user-initiated trading writes; public quotes, history and automatic settlement remain readable. */
@Configuration
@RequiredArgsConstructor
public class TradeKycGate implements WebMvcConfigurer, HandlerInterceptor {
    private final KycIdentityService identity;

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/trade/**", "/api/financial/purchase", "/api/financial/redeem/**");
    }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!java.util.Arrays.asList("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                && request.getUserPrincipal() != null) {
            identity.requireTradingApproved(Long.valueOf(request.getUserPrincipal().getName()));
        }
        return true;
    }
}
