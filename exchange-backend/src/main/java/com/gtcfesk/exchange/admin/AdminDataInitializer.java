package com.gtcfesk.exchange.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminDataInitializer implements CommandLineRunner {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (adminUserRepository.count() == 0) {
            AdminUser user = new AdminUser();
            user.setAccount("admin");
            user.setEmail("admin@example.com");
            user.setRole("super_admin");
            user.setEnabled(true);
            user.setPasswordHash(passwordEncoder.encode("123456"));
            adminUserRepository.save(user);
            System.out.println("=== 初始化默认管理员账号 ===");
            System.out.println("账号: admin");
            System.out.println("密码: 123456");
            System.out.println("========================");
        }
    }
}

