package com.gtcfesk.exchange.auth;

import com.gtcfesk.exchange.auth.dto.*;
import com.gtcfesk.exchange.common.*;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import javax.validation.Validation;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class RegistrationWithoutCodeTest {
    @Test void registrationAllowsMissingInvitationAndEmailCodeButRequiresCaptcha() {
        VerifyCodeRepository codes = mock(VerifyCodeRepository.class);
        EmailService email = mock(EmailService.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AssetAccountRepository assets = mock(AssetAccountRepository.class);
        AuthService service = new AuthService(codes, email, users, encoder, assets);
        com.gtcfesk.exchange.security.RegistrationSecurity captcha = mock(com.gtcfesk.exchange.security.RegistrationSecurity.class);
        ReflectionTestUtils.setField(service, "registrationSecurity", captcha);
        ReflectionTestUtils.setField(service,"tenantPolicy",mock(com.gtcfesk.exchange.control.TenantPolicyService.class));
        com.gtcfesk.exchange.admin.SystemConfigService configs = mock(com.gtcfesk.exchange.admin.SystemConfigService.class);
        when(configs.registrationFields()).thenReturn(RegistrationFields.defaults());
        ReflectionTestUtils.setField(service, "systemConfigService", configs);
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.getExpireSeconds()).thenReturn(3600L);
        ReflectionTestUtils.setField(service, "jwtUtil", jwt);
        when(encoder.encode(anyString())).thenReturn("hashed");
        when(users.saveAndFlush(any())).thenAnswer(call -> {
            UserAccount user = call.getArgument(0);
            user.setId(123L);
            return user;
        });
        RegisterRequest req = new RegisterRequest();
        req.setEmail("registration@example.com");
        req.setPassword("secret123");
        req.setConfirmPassword("secret123");
        req.setCaptchaSession("0123456789abcdef0123456789abcdef");
        req.setCaptchaId("0123456789abcdef0123456789abcdef");
        req.setCaptchaCode("A2B3");
        try (javax.validation.ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(req).isEmpty());
            req.setEmail("invalid");
            assertFalse(factory.getValidator().validate(req).isEmpty());
            req.setEmail("registration@example.com");
        }
        assertNotNull(service.register(req));
        verify(captcha).verifyAndConsume(req.getCaptchaSession(), req.getCaptchaId(), req.getCaptchaCode());
        doThrow(com.gtcfesk.exchange.security.SecurityFailure.invalid()).when(captcha).verifyAndConsume(any(), any(), any());
        int saved = mockingDetails(users).getInvocations().size();
        assertThrows(com.gtcfesk.exchange.security.SecurityFailure.class, () -> service.register(req));
        assertEquals(saved, mockingDetails(users).getInvocations().size());
        reset(captcha);
        verifyNoInteractions(codes, email);
        verify(assets, times(3)).save(any());
        req.setConfirmPassword("different");
        assertThrows(BusinessException.class, () -> service.register(req));
        req.setConfirmPassword(req.getPassword());
        when(users.existsByTenantIdAndEmail(1L, req.getEmail())).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.register(req));
        SendCodeRequest send = new SendCodeRequest();
        send.setEmail(req.getEmail());
        send.setScene("register");
        assertThrows(BusinessException.class, () -> service.sendEmailCode(send));
        send.setScene("forget_password");
        service.sendEmailCode(send);
        verify(email).sendVerificationCode(eq(req.getEmail()), anyString());
        ResetPasswordRequest reset = new ResetPasswordRequest();
        reset.setEmail(req.getEmail());
        reset.setPassword(req.getPassword());
        reset.setConfirmPassword(req.getPassword());
        assertThrows(BusinessException.class, () -> service.resetPassword(reset));
    }
}
