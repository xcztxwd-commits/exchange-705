package com.gtcfesk.exchange.demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import javax.servlet.http.*;

/** Defense against accidental real-account requests from the practice UI, not an authorization bypass. */
@Configuration
public class DemoModeBoundary implements WebMvcConfigurer, HandlerInterceptor {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.simulation.SimulationEnvironment simulation;
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        boolean demo = simulation != null && simulation.enabled();
        response.setHeader("X-Account-Environment", demo ? "DEMO" : "REAL");
        if (demo && com.gtcfesk.exchange.simulation.SimulationAdminQueryBoundary.internalRead(request)) return true;
        if (demo) {
            response.setHeader("Cache-Control", "no-store");
            boolean publicRead = ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && (path.startsWith("/api/market/") || path.startsWith("/api/ws/") || path.equals("/api/user/system/timezone"));
            publicRead = publicRead || ("POST".equals(request.getMethod()) && path.equals("/api/market/price/batch"));
            boolean identityWrite = path.startsWith("/api/auth/") || path.startsWith("/api/admin/") || path.startsWith("/api/user/changePassword");
            if (identityWrite || (!publicRead && !"DEMO".equals(request.getHeader("X-Account-Mode")))) {
                response.setStatus(409); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"ACCOUNT_MODE_MISMATCH\",\"message\":\"独立模拟账户环境不匹配\"}");
                return false;
            }
            return true;
        }
        if (path.startsWith("/api/demo/")) response.setHeader("Cache-Control", "no-store");
        boolean marketRead = path.startsWith("/api/market/") && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()));
        if ("DEMO".equalsIgnoreCase(request.getHeader("X-Account-Mode"))
                && !path.startsWith("/api/demo/") && !marketRead
                && !path.equals("/api/auth/heartbeat")) {
            response.setStatus(409); response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"code\":\"ACCOUNT_MODE_MISMATCH\",\"message\":\"模拟账户不能访问真实账户业务，请先切换账户\"}");
            return false;
        }
        return true;
    }
}
