package com.gtcfesk.exchange.service;

import com.gtcfesk.exchange.admin.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    @Autowired
    private SystemConfigService systemConfigService;

    /**
     * 构建动态 MailSender，优先读取 system_config 中的配置
     */
    private JavaMailSender buildSender() {
        String host = systemConfigService.getConfigValue("mail.host");
        String portStr = systemConfigService.getConfigValue("mail.port");
        String username = systemConfigService.getConfigValue("mail.username");
        String password = systemConfigService.getConfigValue("mail.password");

        if (host == null || username == null || password == null) {
            throw new com.gtcfesk.exchange.common.BusinessException("邮件发送失败：SMTP 配置不完整");
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        try {
            sender.setPort(portStr != null ? Integer.parseInt(portStr) : 465);
        } catch (NumberFormatException e) {
            sender.setPort(465);
        }
        sender.setUsername(username);
        sender.setPassword(password);

        // 常见 SSL/TLS 配置
        java.util.Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.ssl.enable", "true");

        return sender;
    }

    public void sendVerificationCode(String toEmail, String code) {
        String fromEmail = systemConfigService.getConfigValue("mail.from");
        if (fromEmail == null || fromEmail.isEmpty()) {
            fromEmail = "noreply@exchange.com"; // 默认发件人
        }

        JavaMailSender sender = buildSender();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject("验证码");
        message.setText("您的验证码是：" + code + "，有效期10分钟。");

        try {
            sender.send(message);
        } catch (Exception e) {
            System.err.println("邮件发送失败: " + e.getMessage());
            throw new com.gtcfesk.exchange.common.BusinessException("邮件发送失败，请检查 SMTP 配置");
        }
    }

    public void sendPasswordResetNotice(String toEmail) {
        String fromEmail = systemConfigService.getConfigValue("mail.from");
        if (fromEmail == null || fromEmail.isEmpty()) {
            fromEmail = "noreply@exchange.com";
        }

        JavaMailSender sender = buildSender();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject("密码重置通知");
        message.setText("您的密码已被重置，请及时登录并修改密码。");

        try {
            sender.send(message);
        } catch (Exception e) {
            System.err.println("邮件发送失败: " + e.getMessage());
            throw new com.gtcfesk.exchange.common.BusinessException("邮件发送失败，请检查 SMTP 配置");
        }
    }
}

