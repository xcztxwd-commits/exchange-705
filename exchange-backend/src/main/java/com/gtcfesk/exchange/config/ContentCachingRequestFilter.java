package com.gtcfesk.exchange.config;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 请求包装过滤器，用于缓存请求体内容，以便拦截器可以多次读取
 * 需要在JwtFilter之前执行，以便包装请求
 */
@Component
@Order(1)
public class ContentCachingRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 只对管理员API请求进行包装
        String requestURI = request.getRequestURI();
        if (requestURI.startsWith("/api/admin/") && !requestURI.startsWith("/api/admin/operation-logs")) {
            ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
            filterChain.doFilter(wrappedRequest, response);
        } else {
            filterChain.doFilter(request, response);
        }
    }
}

