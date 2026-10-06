package com.gtcfesk.exchange.simulation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;

/** Reject local identity/admin operations before login/captcha/rate-limit filters can process them. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 10) @RequiredArgsConstructor
public class SimulationIdentityBoundary extends OncePerRequestFilter {
    private final SimulationEnvironment environment;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        if (SimulationAdminQueryBoundary.internalRead(request)) return true;
        String path=request.getServletPath();
        if (path == null || path.isEmpty()) path=request.getRequestURI().substring(request.getContextPath().length());
        return !environment.enabled() || "OPTIONS".equals(request.getMethod()) ||
            !(path.startsWith("/api/control/") || path.startsWith("/api/auth/") || path.startsWith("/api/admin/") || path.startsWith("/api/user/changePassword") || path.equals("/api/user/profile") || path.startsWith("/api/user/profile/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException {
        response.setStatus(409);response.setHeader("X-Account-Environment","DEMO");response.setHeader("Cache-Control","no-store");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"ACCOUNT_MODE_MISMATCH\",\"message\":\"模拟环境不提供独立登录或真实身份修改\"}");
    }
}
