package com.gtcfesk.exchange.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expireSeconds}")
    private Long expireSeconds;

    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantRepository tenants;

    public Long getExpireSeconds() {
        return expireSeconds;
    }

    public String generateToken(String subject, Map<String, Object> claims) {
        Map<String,Object> scoped = new java.util.HashMap<>(claims);
        if (!"control".equals(scoped.get("userType")) && !"control_access".equals(scoped.get("userType")))
        {
            Long tenant = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
            scoped.put("tenantId", tenant);
            scoped.put("tenantVersion", tenants.findById(tenant).orElseThrow(IllegalArgumentException::new).getSessionVersion());
        }
        return generateToken(subject, scoped, expireSeconds);
    }

    public String generateToken(String subject, Map<String,Object> claims, long lifetimeSeconds) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + lifetimeSeconds * 1000);
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(SignatureAlgorithm.HS256, secret)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().setSigningKey(secret).parseClaimsJws(token).getBody();
    }

    // Bind signed sessions to current credentials without exposing password hashes.
    public String credentialKey(String passwordHash) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.Base64.getEncoder().encodeToString(mac.doFinal(passwordHash.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Session signing unavailable", e);
        }
    }
}
