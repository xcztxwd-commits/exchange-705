package com.gtcfesk.exchange.config;

import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserAccountRepository userAccountRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                String subject = null;
                boolean tokenValid = false;
                
                // 处理 mock token (开发阶段使用)
                if (token.startsWith("mock-")) {
                    // mock token 格式: mock-{userId}-{tokenId} 或 mock-{userId}（兼容旧格式）
                    String tokenContent = token.substring(5); // 去掉 "mock-" 前缀
                    String[] parts = tokenContent.split("-", 2);
                    
                    if (parts.length >= 1) {
                        String userIdStr = parts[0];
                        String tokenId = parts.length > 1 ? parts[1] : null;
                        
                        // 验证token是否有效（单设备登录）
                        if (tokenId != null) {
                            try {
                                Long userId = Long.parseLong(userIdStr);
                                UserAccount user = userAccountRepository.findById(userId).orElse(null);
                                if (user != null && tokenId.equals(user.getCurrentToken())) {
                                    // token有效，更新最后活动时间（实时在线检测）
                                    user.setLastActivityAt(java.time.LocalDateTime.now());
                                    userAccountRepository.save(user);
                                    subject = userIdStr;
                                    tokenValid = true;
                                } else {
                                    // token无效（可能是其他设备登录了）
                                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                                    response.setContentType("application/json;charset=UTF-8");
                                    response.getWriter().write("{\"success\":false,\"message\":\"账号已在其他设备登录，请重新登录\",\"code\":\"TOKEN_INVALID\"}");
                                    return;
                                }
                            } catch (NumberFormatException e) {
                                // userId格式错误
                            }
                        } else {
                            // 兼容旧格式：mock-{userId}（没有tokenId）
                            try {
                                Long userId = Long.parseLong(userIdStr);
                                UserAccount user = userAccountRepository.findById(userId).orElse(null);
                                if (user != null) {
                                    // 更新最后活动时间（实时在线检测）
                                    user.setLastActivityAt(java.time.LocalDateTime.now());
                                    userAccountRepository.save(user);
                                }
                            } catch (NumberFormatException ex) {
                                // userId格式错误，忽略
                            }
                            subject = userIdStr;
                            tokenValid = true;
                        }
                    }
                } else {
                    // 处理真正的 JWT token
                    Claims claims = jwtUtil.parse(token);
                    subject = claims.getSubject();
                    tokenValid = true;
                    
                    // 更新用户最后活动时间（用于实时在线检测）
                    if (subject != null && !subject.isEmpty()) {
                        try {
                            // 尝试从 subject 中提取用户ID
                            // subject 格式可能是: userId 或 user-{userId} 或 agent-{agentId}
                            String userIdStr = subject;
                            if (subject.startsWith("user-")) {
                                userIdStr = subject.substring(5);
                            } else if (subject.startsWith("agent-")) {
                                userIdStr = subject.substring(6);
                            }
                            
                            Long userId = Long.parseLong(userIdStr);
                            UserAccount user = userAccountRepository.findById(userId).orElse(null);
                            if (user != null) {
                                user.setLastActivityAt(java.time.LocalDateTime.now());
                                userAccountRepository.save(user);
                            }
                        } catch (Exception e) {
                            // 解析失败，忽略（不影响认证流程）
                        }
                    }
                }
                
                if (subject != null && !subject.isEmpty() && tokenValid) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(subject, null, Collections.emptyList());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception e) {
                // 如果是 mock token，尝试从 token 中提取用户ID（兼容处理）
                if (token.startsWith("mock-")) {
                    try {
                        String tokenContent = token.substring(5);
                        String[] parts = tokenContent.split("-", 2);
                        if (parts.length >= 1) {
                            String userIdStr = parts[0];
                            // 如果是旧格式（没有tokenId），允许通过（兼容旧token）
                            if (parts.length == 1) {
                                try {
                                    Long userId = Long.parseLong(userIdStr);
                                    UserAccount user = userAccountRepository.findById(userId).orElse(null);
                                    if (user != null) {
                                        // 更新最后活动时间（实时在线检测）
                                        user.setLastActivityAt(java.time.LocalDateTime.now());
                                        userAccountRepository.save(user);
                                    }
                                } catch (NumberFormatException ex) {
                                    // userId格式错误，忽略
                                }
                                UsernamePasswordAuthenticationToken authentication =
                                        new UsernamePasswordAuthenticationToken(userIdStr, null, Collections.emptyList());
                                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                                SecurityContextHolder.getContext().setAuthentication(authentication);
                            } else {
                                // 新格式需要验证tokenId
                                SecurityContextHolder.clearContext();
                            }
                        }
                    } catch (Exception ignored) {
                        SecurityContextHolder.clearContext();
                    }
                } else {
                    SecurityContextHolder.clearContext();
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}





