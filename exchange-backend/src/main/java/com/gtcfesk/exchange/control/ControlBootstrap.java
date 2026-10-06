package com.gtcfesk.exchange.control;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
/** Explicit first deployment only. No default account/password; no credential logging. */
@Component
public class ControlBootstrap implements CommandLineRunner {
 private final ControlAdminRepository admins;private final PasswordEncoder passwords;private final ControlMfa mfa;
 private final String account,password,seed;private final boolean enabled;
 public ControlBootstrap(ControlAdminRepository admins,PasswordEncoder passwords,ControlMfa mfa,@Value("${platform.bootstrap.enabled:false}") boolean enabled,@Value("${platform.bootstrap.account:}") String account,@Value("${platform.bootstrap.password:}") String password,@Value("${platform.bootstrap.totp-secret:}") String seed){this.admins=admins;this.passwords=passwords;this.mfa=mfa;this.enabled=enabled;this.account=account;this.password=password;this.seed=seed;}
 @Override @Transactional public void run(String...args){if(!enabled)return;if(admins.count()!=0)throw new IllegalStateException("总控初始化已完成，请关闭 bootstrap 配置");if(password.length()<6||password.length()>128||!seed.matches("[A-Z2-7]{32,64}"))throw new IllegalStateException("总控初始化需要 6 至 128 位密码和有效 TOTP 种子");ControlAdmin a=new ControlAdmin();a.setAccount(BackendLoginRegistry.normalize(account));a.setPasswordHash(passwords.encode(password));a.setMfaSecret(mfa.encrypt(seed));a.setMfaEnabled(true);admins.saveAndFlush(a);}
}
