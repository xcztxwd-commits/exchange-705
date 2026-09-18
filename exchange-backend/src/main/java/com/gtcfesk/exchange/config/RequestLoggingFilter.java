package com.gtcfesk.exchange.config;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestURI = request.getRequestURI();
        String method = request.getMethod();
        
        // 只记录图片相关的请求
        if (requestURI.contains("/uploads/images/") || requestURI.contains("/api/uploads/images/")) {
            System.out.println("[RequestLoggingFilter] 收到请求: " + method + " " + requestURI);
            System.out.println("[RequestLoggingFilter] QueryString: " + request.getQueryString());
            System.out.println("[RequestLoggingFilter] RemoteAddr: " + request.getRemoteAddr());
        }
        
        filterChain.doFilter(request, response);
        
        // 记录响应状态
        if (requestURI.contains("/uploads/images/") || requestURI.contains("/api/uploads/images/")) {
            System.out.println("[RequestLoggingFilter] 响应状态: " + response.getStatus() + " for " + requestURI);
        }
    }
}



