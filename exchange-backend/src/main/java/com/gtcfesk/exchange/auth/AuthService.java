package com.gtcfesk.exchange.auth;

import com.gtcfesk.exchange.auth.dto.LoginRequest;
import com.gtcfesk.exchange.auth.dto.RegisterRequest;
import com.gtcfesk.exchange.auth.dto.ResetPasswordRequest;
import com.gtcfesk.exchange.auth.dto.SendCodeRequest;
import com.gtcfesk.exchange.auth.vo.AuthResponse;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.VerifyCode;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.repository.VerifyCodeRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.service.EmailService;
import com.gtcfesk.exchange.utils.IpUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.gtcfesk.exchange.common.JwtUtil jwtUtil;

    @org.springframework.beans.factory.annotation.Autowired
    private com.gtcfesk.exchange.security.RegistrationSecurity registrationSecurity;

    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService tenantPolicy;

    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.admin.SystemConfigService systemConfigService;

    private final VerifyCodeRepository verifyCodeRepository;
    private final EmailService emailService;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AssetAccountRepository assetAccountRepository;

    public AuthService(VerifyCodeRepository verifyCodeRepository,
                       EmailService emailService,
                       UserAccountRepository userAccountRepository,
                       PasswordEncoder passwordEncoder,
                       AssetAccountRepository assetAccountRepository) {
        this.verifyCodeRepository = verifyCodeRepository;
        this.emailService = emailService;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.assetAccountRepository = assetAccountRepository;
    }

    @org.springframework.transaction.annotation.Transactional
    public AuthResponse login(LoginRequest req) {
        req.setAccount(req.getAccount().trim().toLowerCase(java.util.Locale.ROOT));
        tenantPolicy.requireLogin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        UserAccount user = userAccountRepository.findByTenantIdAndEmail(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), req.getAccount())
                .orElseThrow(() -> new BusinessException("user not found"));

        // 允许 normal 和 active 状态的用户登录，排除 disabled, frozen, banned
        String status = user.getStatus() != null ? user.getStatus().toLowerCase() : "";
        if (!"normal".equals(status) && !"active".equals(status)) {
            throw new BusinessException("account disabled");
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BusinessException("password wrong");
        }

        // 生成新的token标识（用于单设备登录）
        String tokenId = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        
        // 记录登录IP、地区和域名，并更新当前token
        try {
            HttpServletRequest request = getCurrentRequest();
            if (request != null) {
                String ip = IpUtils.getClientIp(request);
                String region = IpUtils.getRegionByIp(ip);
                String domain = com.gtcfesk.exchange.utils.DomainUtils.getLoginDomain(request);
                user.setLastLoginIp(ip);
                
                user.setLastLoginRegion(region);
                user.setLastLoginDomain(domain);
                user.setLastLoginAt(LocalDateTime.now());
            }
            // 更新当前有效的token标识（新设备登录会使旧设备token失效）
            user.setCurrentToken(tokenId);
            // 初始化最后活动时间
            user.setLastActivityAt(LocalDateTime.now());
            userAccountRepository.save(user);
        } catch (Exception e) {
            throw new BusinessException("登录失败，请重试");
        }

        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", user.getId());
        userMap.put("tenantId", com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        userMap.put("email", user.getEmail());
        userMap.put("nickname", user.getNickname());
        userMap.put("status", user.getStatus());
        // 签名会话绑定当前凭据和单设备会话标识
        Map<String, Object> claims = new HashMap<>();
        claims.put("userType", "user");
        claims.put("sid", tokenId);
        claims.put("credential", jwtUtil.credentialKey(user.getPasswordHash()));
        String signedToken = jwtUtil.generateToken("user-" + user.getId(), claims);
        return new AuthResponse(signedToken, System.currentTimeMillis() + jwtUtil.getExpireSeconds() * 1000, userMap);
    }

    /**
     * 获取当前HTTP请求
     */
    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }

    @org.springframework.transaction.annotation.Transactional
    public AuthResponse register(RegisterRequest req) {
        tenantPolicy.requireNewBusiness("registration");
        String normalizedEmail = req.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
        if (normalizedEmail.length() > 128) throw new BusinessException("email too long");
        req.setEmail(normalizedEmail);
        registrationSecurity.verifyAndConsume(req.getCaptchaSession(), req.getCaptchaId(), req.getCaptchaCode());
        if (!java.util.Objects.equals(req.getPassword(), req.getConfirmPassword())) {
            throw new BusinessException("两次密码不一致");
        }
        if (userAccountRepository.existsByTenantIdAndEmail(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), req.getEmail())) {
            throw new BusinessException("email exists");
        }

        // Registration email verification is temporarily disabled; password reset still requires it.
        UserAccount user = new UserAccount();
        user.setEmail(req.getEmail());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setInviteCode(req.getInvitationCode());
        int nicknameEnd = normalizedEmail.offsetByCodePoints(0, Math.min(50, normalizedEmail.codePointCount(0, normalizedEmail.length())));
        user.setNickname(normalizedEmail.substring(0, nicknameEnd));
        systemConfigService.registrationFields().apply(req, user);
        if (user.getPhone() != null && userAccountRepository.findByTenantIdAndPhone(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), user.getPhone()).isPresent()) {
            throw new BusinessException("phone exists");
        }
        
        // 处理邀请码：如果提供了邀请码，查找上级用户并记录
        if (req.getInvitationCode() != null && !req.getInvitationCode().isEmpty()) {
            userAccountRepository.findByTenantIdAndMyInviteCode(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), req.getInvitationCode())
                    .ifPresent(parentUser -> {
                        user.setParentUserId(parentUser.getId());
                    });
        }
        
        // 生成用户自己的邀请码
        user.setMyInviteCode(generateInviteCode());
        
        try {
            // Flush identity constraints before any account/session side effects; repository ownership guards remain active.
            userAccountRepository.saveAndFlush(user);
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            if (registrationIdentityConflict(conflict)) {
                BusinessException denied = new BusinessException("registration identity exists");
                denied.initCause(conflict); // Preserve the actual database category internally, never in the HTTP response.
                throw denied;
            }
            throw conflict; // Foreign-key and other integrity failures are not disguised as duplicate registration.
        }

        // 初始化三类资产账户：资金 / 合约 / 期权
        createIfNotExists(user.getId(), "FUND");
        createIfNotExists(user.getId(), "CONTRACT");
        createIfNotExists(user.getId(), "OPTION");

        // 生成新的token标识（用于单设备登录）
        String tokenId = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        user.setCurrentToken(tokenId);
        // 初始化最后活动时间
        user.setLastActivityAt(LocalDateTime.now());
        userAccountRepository.save(user);

        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", user.getId());
        userMap.put("tenantId", com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        userMap.put("email", user.getEmail());
        userMap.put("nickname", user.getNickname());
        // 签名会话绑定当前凭据和单设备会话标识
        Map<String, Object> claims = new HashMap<>();
        claims.put("userType", "user");
        claims.put("sid", tokenId);
        claims.put("credential", jwtUtil.credentialKey(user.getPasswordHash()));
        String signedToken = jwtUtil.generateToken("user-" + user.getId(), claims);
        return new AuthResponse(signedToken, System.currentTimeMillis() + jwtUtil.getExpireSeconds() * 1000, userMap);
    }

    private static boolean registrationIdentityConflict(Throwable failure) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.size() < 12 && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException && ((java.sql.SQLException) cause).getErrorCode() == 1062) return true;
            if (cause instanceof org.hibernate.exception.ConstraintViolationException) {
                String constraint = ((org.hibernate.exception.ConstraintViolationException) cause).getConstraintName();
                if ("uk_tenant_normalized_email".equals(constraint) || "uk_tenant_normalized_phone".equals(constraint)) return true;
            }
        }
        return false;
    }

    public void sendEmailCode(SendCodeRequest req) {
        req.setEmail(req.getEmail().trim().toLowerCase(java.util.Locale.ROOT));
        if (!java.util.Arrays.asList("forget_password", "change_password").contains(req.getScene())) {
            throw new BusinessException("验证码用途无效");
        }
        String code = String.valueOf(100000 + new java.security.SecureRandom().nextInt(900000)); // 六位

        VerifyCode vc = new VerifyCode();
        vc.setEmail(req.getEmail());
        vc.setScene(req.getScene());
        vc.setCode(code);
        vc.setExpireAt(LocalDateTime.now().plusMinutes(10));
        // Validate outbound policy/rate limits and deliver before persisting an unusable code.
        emailService.sendVerificationCode(req.getEmail(), code);
        verifyCodeRepository.save(vc);
    }

    @org.springframework.transaction.annotation.Transactional(noRollbackFor = BusinessException.class)
    public void resetPassword(ResetPasswordRequest req) {
        req.setEmail(req.getEmail().trim().toLowerCase(java.util.Locale.ROOT));
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new BusinessException("两次密码不一致");
        }
        
        // 支持多种场景：forget_password 和 change_password
        String scene = req.getScene() != null ? req.getScene() : "forget_password";
        
        if (!"forget_password".equals(scene) && !"change_password".equals(scene)) {
            throw new BusinessException("验证码用途无效");
        }
        VerifyCode latest = verifyCodeRepository.findTopByTenantIdAndEmailAndSceneOrderByIdDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), req.getEmail(), scene)
                .orElseThrow(() -> new BusinessException("验证码不存在，请重新发送"));

        validateCode(latest, req.getVerifyCode());

        UserAccount user = userAccountRepository.findByTenantIdAndEmail(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), req.getEmail())
                .orElseThrow(() -> new BusinessException("user not found"));
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setCurrentToken(null);
        latest.setCode("");
        latest.setExpireAt(LocalDateTime.now().minusSeconds(1));
        verifyCodeRepository.save(latest);
        userAccountRepository.save(user);
    }

    private void validateCode(VerifyCode latest, String supplied) {
        if (!latest.getExpireAt().isAfter(LocalDateTime.now()) || latest.getFailedAttempts() >= 5) {
            throw new BusinessException("验证码已过期或尝试次数过多，请重新发送");
        }
        if (!latest.getCode().equals(supplied)) {
            latest.setFailedAttempts(latest.getFailedAttempts() + 1);
            verifyCodeRepository.save(latest);
            throw new BusinessException("验证码错误");
        }
    }

    private void createIfNotExists(Long userId, String coin) {
        assetAccountRepository.findByTenantIdAndUserIdAndCoin(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId, coin)
                .orElseGet(() -> {
                    AssetAccount a = new AssetAccount();
                    a.setUserId(userId);
                    a.setCoin(coin);
                    return assetAccountRepository.save(a);
                });
    }

    /**
     * 生成邀请码（6位大写字母+数字）
     */
    private String generateInviteCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 排除容易混淆的字符
        StringBuilder code = new StringBuilder();
        
        // 生成随机邀请码
        for (int i = 0; i < 6; i++) {
            code.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        
        // 确保唯一性
        String baseCode = code.toString();
        String finalCode = baseCode;
        int suffix = 0;
        while (userAccountRepository.findByTenantIdAndMyInviteCode(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), finalCode).isPresent()) {
            suffix++;
            finalCode = baseCode.substring(0, 5) + chars.charAt(suffix % chars.length());
        }
        
        return finalCode;
    }
}

