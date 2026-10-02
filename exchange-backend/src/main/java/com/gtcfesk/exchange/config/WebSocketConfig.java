package com.gtcfesk.exchange.config;

import com.gtcfesk.exchange.market.MarketWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 * 用于市场数据的实时推送
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    
    @Autowired
    private MarketWebSocketHandler marketWebSocketHandler;
    
    @Autowired private com.gtcfesk.exchange.control.TenantHostService tenantHosts;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(marketWebSocketHandler, "/api/ws/market")
                .addInterceptors(new org.springframework.web.socket.server.HandshakeInterceptor() {
                    public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request, org.springframework.http.server.ServerHttpResponse response, org.springframework.web.socket.WebSocketHandler handler, java.util.Map<String,Object> attributes) {
                        if (!(request instanceof org.springframework.http.server.ServletServerHttpRequest)) return false;
                        try {
                            javax.servlet.http.HttpServletRequest servlet = ((org.springframework.http.server.ServletServerHttpRequest)request).getServletRequest();
                            com.gtcfesk.exchange.control.Tenant tenant = tenantHosts.resolve(servlet);
                            if (!tenant.isDomainVerified() || "DISABLED".equals(tenant.getStatus()) || !tenant.getId().equals(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId())) return false;
                            if (!java.util.Objects.equals(servlet.getHeader("Origin"), tenantHosts.frontendOrigin(tenant.getFrontendHost()))) return false;
                            attributes.put("tenantId", tenant.getId());attributes.put("frontendHost", tenant.getFrontendHost());return true;
                        } catch (RuntimeException e) { return false; }
                    }
                    public void afterHandshake(org.springframework.http.server.ServerHttpRequest r, org.springframework.http.server.ServerHttpResponse s, org.springframework.web.socket.WebSocketHandler h, Exception ex) {}
                }).setAllowedOriginPatterns("*"); // Dynamic origins are checked exactly by the handshake interceptor above.
    }
}

