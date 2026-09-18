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

    public AuthResponse login(LoginRequest req) {
        // 先尝试管理员登录
        AdminUser admin = adminUserRepository.findByAccount(req.getAccount())
                .orElseGet(() -> adminUserRepository.findByEmail(req.getAccount()).orElse(null));
        
        if (admin != null) {
            // 管理员登录逻辑
            if (!admin.getEnabled()) {
                throw new BusinessException("账号已被禁用");
            }
            
            if (!passwordEncoder.matches(req.getPassword(), admin.getPasswordHash())) {
                throw new BusinessException("密码错误");
            }
            
            Map<String, Object> user = new HashMap<>();
            user.put("id", admin.getId());
            user.put("account", admin.getAccount());
            user.put("email", admin.getEmail());
            user.put("role", admin.getRole());
            user.put("userType", "admin"); // 标识为管理员
            user.put("isSuperAdmin", "super_admin".equals(admin.getRole()));
            
            String token = jwtUtil.generateToken(String.valueOf(admin.getId()), user);
            long expire = System.currentTimeMillis() + jwtUtil.getExpireSeconds() * 1000;
            
            return new AuthResponse(token, expire, user);
        }
        
        // 尝试代理用户登录
        UserAccount agent = userAccountRepository.findByEmail(req.getAccount()).orElse(null);
        
        if (agent != null && "agent".equals(agent.getUserType())) {
            // 代理用户登录逻辑
            if (!"normal".equalsIgnoreCase(agent.getStatus()) && !"active".equalsIgnoreCase(agent.getStatus())) {
                throw new BusinessException("账号已被禁用");
            }
            
            if (!passwordEncoder.matches(req.getPassword(), agent.getPasswordHash())) {
                throw new BusinessException("密码错误");
            }
            
            Map<String, Object> user = new HashMap<>();
            user.put("id", agent.getId());
            user.put("account", agent.getEmail());
            user.put("email", agent.getEmail());
            user.put("nickname", agent.getNickname());
            user.put("userType", "agent"); // 标识为代理
            user.put("isSuperAdmin", false);
            
            String token = jwtUtil.generateToken("agent-" + agent.getId(), user);
            long expire = System.currentTimeMillis() + jwtUtil.getExpireSeconds() * 1000;
            
            return new AuthResponse(token, expire, user);
        }
        
        throw new BusinessException("账号不存在");
    }

    /**
     * 更新管理员登录名称
     */
    public void updateAccount(Long adminId, String newAccount) {
        AdminUser admin = adminUserRepository.findById(adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        
        // 检查新账户名是否已被使用
        if (!admin.getAccount().equals(newAccount)) {
            if (adminUserRepository.findByAccount(newAccount).isPresent()) {
                throw new BusinessException("该登录名称已被使用");
            }
        }
        
        admin.setAccount(newAccount);
        adminUserRepository.save(admin);
    }

    /**
     * 修改管理员密码
     */
    public void changePassword(Long adminId, String oldPassword, String newPassword) {
        AdminUser admin = adminUserRepository.findById(adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        
        // 验证旧密码
        if (!passwordEncoder.matches(oldPassword, admin.getPasswordHash())) {
            throw new BusinessException("当前密码错误");
        }
        
        // 验证新密码长度
        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException("新密码长度不能少于6个字符");
        }
        
        // 更新密码
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        adminUserRepository.save(admin);
    }

    /**
     * 更新代理邮箱（账户名）
     */
    public void updateAgentEmail(Long agentId, String newEmail) {
        UserAccount agent = userAccountRepository.findById(agentId)
                .orElseThrow(() -> new BusinessException("代理不存在"));
        
        // 确保是代理用户
        if (!"agent".equals(agent.getUserType())) {
            throw new BusinessException("该用户不是代理用户");
        }
        
        // 检查新邮箱是否已被使用
        if (!agent.getEmail().equals(newEmail)) {
            if (userAccountRepository.findByEmail(newEmail).isPresent()) {
                throw new BusinessException("该邮箱已被使用");
            }
        }
        
        agent.setEmail(newEmail);
        userAccountRepository.save(agent);
    }

    /**
     * 修改代理密码
     */
    public void changeAgentPassword(Long agentId, String oldPassword, String newPassword) {
        UserAccount agent = userAccountRepository.findById(agentId)
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
        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException("新密码长度不能少于6个字符");
        }
        
        // 更新密码
        agent.setPasswordHash(passwordEncoder.encode(newPassword));
        userAccountRepository.save(agent);
    }
}

