package com.gtcfesk.exchange.config;

import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.admin.AdminUser;
import com.gtcfesk.exchange.admin.AdminUserRepository;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final UserAccountRepository userAccountRepository;
    private final AdminUserRepository adminUserRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return java.util.Arrays.asList("/api/auth/login", "/api/auth/register", "/api/auth/sendEmailCode", "/api/auth/resetPassword", "/api/admin/auth/login").contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parse(header.substring(7));
                String type = claims.get("userType", String.class);
                String prefix = type + "-";
                if (claims.getExpiration() == null || claims.getSubject() == null || !claims.getSubject().startsWith(prefix)) {
                    throw new IllegalArgumentException();
                }
                Long id = Long.valueOf(claims.getSubject().substring(prefix.length()));
                String principal;
                String role;
                String passwordHash;
                if ("admin".equals(type)) {
                    AdminUser admin = adminUserRepository.findById(id).orElseThrow(IllegalArgumentException::new);
                    if (!Boolean.TRUE.equals(admin.getEnabled())) throw new IllegalArgumentException();
                    if (admin.getCurrentToken() == null || !admin.getCurrentToken().equals(claims.get("sid"))) throw new IllegalArgumentException();
                    passwordHash = admin.getPasswordHash();
                    principal = id.toString();
                    role = "super_admin".equals(admin.getRole()) ? "SUPER_ADMIN" : "ADMIN";
                } else if ("user".equals(type) || "agent".equals(type)) {
                    UserAccount user = userAccountRepository.findById(id).orElseThrow(IllegalArgumentException::new);
                    if (!"normal".equalsIgnoreCase(user.getStatus()) && !"active".equalsIgnoreCase(user.getStatus())) throw new IllegalArgumentException();
                    if (user.getCurrentToken() == null || !user.getCurrentToken().equals(claims.get("sid"))) throw new IllegalArgumentException();
                    if ("agent".equals(type) && !"agent".equals(user.getUserType())) throw new IllegalArgumentException();
                    passwordHash = user.getPasswordHash();
                    principal = "agent".equals(type) ? "agent-" + id : id.toString();
                    role = "agent".equals(type) ? "AGENT" : "USER";
                    if (!jwtUtil.credentialKey(passwordHash).equals(claims.get("credential"))) throw new IllegalArgumentException();
                    userAccountRepository.touchActivity(id, java.time.LocalDateTime.now());
                } else {
                    throw new IllegalArgumentException();
                }
                if (!jwtUtil.credentialKey(passwordHash).equals(claims.get("credential"))) throw new IllegalArgumentException();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))));
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"登录已失效，请重新登录\",\"code\":\"TOKEN_INVALID\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
