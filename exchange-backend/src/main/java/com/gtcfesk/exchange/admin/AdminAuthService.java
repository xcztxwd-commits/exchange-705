package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.auth.dto.LoginRequest;
import com.gtcfesk.exchange.auth.vo.AuthResponse;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.BackendLoginRegistry logins;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantRepository tenants;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantPolicyService policy;

    @org.springframework.transaction.annotation.Transactional
    public AuthResponse login(LoginRequest req) {
        com.gtcfesk.exchange.control.BackendLogin entry = logins.resolve(req.getAccount());
        policy.requireLogin(entry.getTenantId());
        try (com.gtcfesk.exchange.tenant.TenantContext.Scope scope = com.gtcfesk.exchange.tenant.TenantContext.open(entry.getTenantId())) {
            Map<String,Object> user = new HashMap<>();
            String passwordHash, sid = java.util.UUID.randomUUID().toString(), subject;
            if ("ADMIN".equals(entry.getSubjectType())) {
                AdminUser admin = adminUserRepository.findByTenantIdAndId(entry.getTenantId(), entry.getAdminUserId()).orElseThrow(() -> new BusinessException("账号或密码错误"));
                if (!Boolean.TRUE.equals(admin.getEnabled()) || !passwordEncoder.matches(req.getPassword(),admin.getPasswordHash())) throw new BusinessException("账号或密码错误");
                admin.setCurrentToken(sid);adminUserRepository.saveAndFlush(admin);
                passwordHash=admin.getPasswordHash();subject="admin-"+admin.getId();
                user.put("mustChangePassword",admin.isMustChangePassword());user.put("id",admin.getId());user.put("email",admin.getEmail());user.put("role",admin.getRole());user.put("userType","admin");user.put("isSuperAdmin","super_admin".equals(admin.getRole()));
            } else if ("AGENT".equals(entry.getSubjectType())) {
                UserAccount agent = userAccountRepository.findByTenantIdAndId(entry.getTenantId(),entry.getUserId()).orElseThrow(() -> new BusinessException("账号或密码错误"));
                if (!"agent".equals(agent.getUserType()) || !java.util.Arrays.asList("normal","active").contains(agent.getStatus()) || !passwordEncoder.matches(req.getPassword(),agent.getPasswordHash())) throw new BusinessException("账号或密码错误");
                agent.setCurrentToken(sid);userAccountRepository.saveAndFlush(agent);
                passwordHash=agent.getPasswordHash();subject="agent-"+agent.getId();
                user.put("id",agent.getId());user.put("email",agent.getEmail());user.put("userType","agent");user.put("isSuperAdmin",false);
            } else throw new BusinessException("账号或密码错误");
            user.put("account",entry.getNormalizedAccount());user.put("tenantId",entry.getTenantId());user.put("tenantName",tenants.findById(entry.getTenantId()).orElseThrow(IllegalArgumentException::new).getName());
            Map<String,Object> claims=new HashMap<>(user);claims.put("sid",sid);claims.put("credential",jwtUtil.credentialKey(passwordHash));
            return new AuthResponse(jwtUtil.generateToken(subject,claims),System.currentTimeMillis()+jwtUtil.getExpireSeconds()*1000,user);
        }
    }

    /**
     * 更新管理员登录名称
     */
    @org.springframework.transaction.annotation.Transactional
    public void updateAccount(Long adminId, String newAccount) {
        AdminUser admin = adminUserRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        
        // 检查新账户名是否已被使用
        if (!admin.getAccount().equals(newAccount)) {
            if (adminUserRepository.findByTenantIdAndAccount(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), newAccount).isPresent()) {
                throw new BusinessException("该登录名称已被使用");
            }
        }
        
        logins.register("ADMIN", adminId, newAccount);
        admin.setAccount(com.gtcfesk.exchange.control.BackendLoginRegistry.normalize(newAccount));
        admin.setCurrentToken(null);
        adminUserRepository.save(admin);
    }

    /**
     * 修改管理员密码
     */
    @org.springframework.transaction.annotation.Transactional
    public void changePassword(Long adminId, String oldPassword, String newPassword) {
        AdminUser admin = adminUserRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        
        // 验证旧密码
        if (!passwordEncoder.matches(oldPassword, admin.getPasswordHash())) {
            throw new BusinessException("当前密码错误");
        }
        
        // 验证新密码长度
        if (newPassword == null || newPassword.length() < 12 || newPassword.length() > 128) {
            throw new BusinessException("新密码长度须为12至128个字符");
        }
        
        // 更新密码
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        admin.setMustChangePassword(false);
        admin.setCurrentToken(null);
        adminUserRepository.save(admin);
    }

    /**
     * 更新代理邮箱（账户名）
     */
    @org.springframework.transaction.annotation.Transactional
    public void updateAgentEmail(Long agentId, String newEmail) {
        if(newEmail==null||!newEmail.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))throw new BusinessException("邮箱无效");
        newEmail=newEmail.trim().toLowerCase(java.util.Locale.ROOT);
        UserAccount agent = userAccountRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), agentId)
                .orElseThrow(() -> new BusinessException("代理不存在"));
        
        // 确保是代理用户
        if (!"agent".equals(agent.getUserType())) {
            throw new BusinessException("该用户不是代理用户");
        }
        
        // 检查新邮箱是否已被使用
        if (!agent.getEmail().equals(newEmail)) {
            if (userAccountRepository.findByTenantIdAndEmail(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), newEmail).isPresent()) {
                throw new BusinessException("该邮箱已被使用");
            }
        }
        
        logins.register("AGENT",agentId,newEmail);
        agent.setEmail(newEmail);
        agent.setCurrentToken(null);
        userAccountRepository.save(agent);
    }

    /**
     * 修改代理密码
     */
    @org.springframework.transaction.annotation.Transactional
    public void changeAgentPassword(Long agentId, String oldPassword, String newPassword) {
        UserAccount agent = userAccountRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), agentId)
                .orElseThrow(() -> new BusinessException("代理不存在"));
        
        // 确保是代理用户
        if (!"agent".equals(agent.getUserType())) {
            throw new BusinessException("该用户不是代理用户");
        }
        
        // 验证旧密码
        if (!passwordEncoder.matches(oldPassword, agent.getPasswordHash())) {
            throw new BusinessException("当前密码错误");
        }
        
        // 验证新密码长度
        if (newPassword == null || newPassword.length() < 12 || newPassword.length() > 128) {
            throw new BusinessException("新密码长度须为12至128个字符");
        }
        
        // 更新密码
        agent.setPasswordHash(passwordEncoder.encode(newPassword));
        agent.setCurrentToken(null);
        userAccountRepository.save(agent);
    }
}

