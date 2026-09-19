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
        UserAccount user = userAccountRepository.findByEmail(req.getAccount())
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

    @org.springframework.transaction.annotation.Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse register(RegisterRequest req) {
        if (!java.util.Objects.equals(req.getPassword(), req.getConfirmPassword())) {
            throw new BusinessException("两次密码不一致");
        }
        if (userAccountRepository.existsByEmail(req.getEmail())) {
            throw new BusinessException("email exists");
        }

        VerifyCode latest = verifyCodeRepository
                .findTopByEmailAndSceneOrderByIdDesc(req.getEmail(), "register")
                .orElseThrow(() -> new BusinessException("code not found"));

        validateCode(latest, req.getVerifyCode());

        latest.setCode("");
        latest.setExpireAt(LocalDateTime.now().minusSeconds(1));
        verifyCodeRepository.save(latest);
        UserAccount user = new UserAccount();
        user.setEmail(req.getEmail());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setInviteCode(req.getInvitationCode());
        user.setNickname(req.getEmail());
        
        // 处理邀请码：如果提供了邀请码，查找上级用户并记录
        if (req.getInvitationCode() != null && !req.getInvitationCode().isEmpty()) {
            userAccountRepository.findByMyInviteCode(req.getInvitationCode())
                    .ifPresent(parentUser -> {
                        user.setParentUserId(parentUser.getId());
                    });
        }
        
        // 生成用户自己的邀请码
        user.setMyInviteCode(generateInviteCode());
        
        userAccountRepository.save(user);

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

    public void sendEmailCode(SendCodeRequest req) {
        if (!java.util.Arrays.asList("register", "forget_password", "change_password").contains(req.getScene())) {
            throw new BusinessException("验证码用途无效");
        }
        String code = String.valueOf(100000 + new java.security.SecureRandom().nextInt(900000)); // 六位

        VerifyCode vc = new VerifyCode();
        vc.setEmail(req.getEmail());
        vc.setScene(req.getScene());
        vc.setCode(code);
        vc.setExpireAt(LocalDateTime.now().plusMinutes(10));
        verifyCodeRepository.save(vc);

        // 发送邮件
        emailService.sendVerificationCode(req.getEmail(), code);
    }

    @org.springframework.transaction.annotation.Transactional(noRollbackFor = BusinessException.class)
    public void resetPassword(ResetPasswordRequest req) {
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new BusinessException("两次密码不一致");
        }
        
        // 支持多种场景：forget_password 和 change_password
        String scene = req.getScene() != null ? req.getScene() : "forget_password";
        
        if (!"forget_password".equals(scene) && !"change_password".equals(scene)) {
            throw new BusinessException("验证码用途无效");
        }
        VerifyCode latest = verifyCodeRepository
                .findTopByEmailAndSceneOrderByIdDesc(req.getEmail(), scene)
                .orElseThrow(() -> new BusinessException("验证码不存在，请重新发送"));

        validateCode(latest, req.getVerifyCode());

        UserAccount user = userAccountRepository.findByEmail(req.getEmail())
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
        assetAccountRepository.findByUserIdAndCoin(userId, coin)
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
        while (userAccountRepository.findByMyInviteCode(finalCode).isPresent()) {
            suffix++;
            finalCode = baseCode.substring(0, 5) + chars.charAt(suffix % chars.length());
        }
        
        return finalCode;
    }
}

