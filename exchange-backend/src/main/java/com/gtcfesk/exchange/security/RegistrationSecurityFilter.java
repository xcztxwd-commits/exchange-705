package com.gtcfesk.exchange.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;

/** Run before body validation so malformed and direct API requests are rate limited too. */
@Component
@Order(2)
public class RegistrationSecurityFilter extends OncePerRequestFilter {
    private final RegistrationSecurity security;
    private final List<IpAddressMatcher> proxies = new ArrayList<>();
    public RegistrationSecurityFilter(RegistrationSecurity security,
        @Value("${security.trusted-proxies:127.0.0.1/32,::1/128}") String trusted) {
        this.security = security;
        for (String cidr : trusted.split(",")) if (!cidr.trim().isEmpty()) proxies.add(new IpAddressMatcher(cidr.trim()));
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = endpoint(request);
        return !"POST".equals(request.getMethod()) ||
            !("/api/auth/captcha".equals(path) || "/api/auth/register".equals(path));
    }
    private static String endpoint(HttpServletRequest request) {
        // Spring MVC accepts a trailing slash; it must not bypass network limits.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
    String clientAddress(HttpServletRequest request) {
        String peer = request.getRemoteAddr(), supplied = request.getHeader("X-Real-IP");
        if (supplied != null && supplied.length() <= 45 && supplied.matches("[0-9a-fA-F:.]+")
            && proxies.stream().anyMatch(p -> p.matches(peer))) {
            try { return java.net.InetAddress.getByName(supplied).getHostAddress(); }
            catch (Exception ignored) { }
        }
        return peer;
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws IOException, ServletException {
        res.setHeader("Cache-Control", "no-store");
        try { security.limitNetwork(endpoint(req).endsWith("/captcha") ? "captcha" : "register", clientAddress(req)); }
        catch (SecurityFailure e) {
            res.setStatus(e.status); res.setContentType("application/json;charset=UTF-8");
            if (e.retryAfter > 0) res.setHeader("Retry-After", Long.toString(e.retryAfter));
            new ObjectMapper().writeValue(res.getOutputStream(), body(e)); return;
        }
        chain.doFilter(req, res);
    }
    public static Map<String,Object> body(SecurityFailure e) {
        Map<String,Object> body = new HashMap<>();
        body.put("success", false); body.put("code", e.code); body.put("retryAfter", e.retryAfter);
        body.put("message", e.status == 429 ? "Too many requests. Please try again later."
            : e.status == 503 ? "Security verification is unavailable. Please try again later."
            : "The CAPTCHA is invalid or expired. Please refresh it.");
        return body;
    }
}
