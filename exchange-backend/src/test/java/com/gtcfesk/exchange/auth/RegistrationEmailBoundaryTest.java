package com.gtcfesk.exchange.auth;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.auth.dto.RegisterRequest;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.repository.VerifyCodeRepository;
import com.gtcfesk.exchange.security.RegistrationSecurity;
import com.gtcfesk.exchange.service.EmailService;
import com.gtcfesk.exchange.tenant.TenantOneFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.validation.Validation;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(TenantOneFixture.class)
class RegistrationEmailBoundaryTest {
    @Test void registrationKeepsFullEmailAndLeavesNicknameUnset() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        AssetAccountRepository assets = mock(AssetAccountRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthService service = new AuthService(mock(VerifyCodeRepository.class), mock(EmailService.class), users, encoder, assets);
        RegistrationSecurity captcha = mock(RegistrationSecurity.class);
        SystemConfigService configs = mock(SystemConfigService.class);
        JwtUtil jwt = mock(JwtUtil.class);
        ReflectionTestUtils.setField(service, "registrationSecurity", captcha);
        ReflectionTestUtils.setField(service, "tenantPolicy", mock(com.gtcfesk.exchange.control.TenantPolicyService.class));
        ReflectionTestUtils.setField(service, "systemConfigService", configs);
        ReflectionTestUtils.setField(service, "jwtUtil", jwt);
        when(configs.registrationFields()).thenReturn(RegistrationFields.defaults());
        when(encoder.encode(any())).thenReturn("hashed");
        when(jwt.getExpireSeconds()).thenReturn(3600L);
        AtomicReference<UserAccount> saved = new AtomicReference<>();
        when(users.saveAndFlush(any())).thenAnswer(call -> {
            UserAccount user = call.getArgument(0);
            user.setId(123L);
            saved.set(user);
            return user;
        });
        String domain = "@example.invalid";
        String longest = repeat('a', 60) + "@" + repeat('b', 60) + ".c.test";
        assertEquals(128, longest.length());
        try (javax.validation.ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            for (int length : new int[]{49, 50, 51, 128}) {
                String email = length == 128 ? longest : repeat('a', length - domain.length()) + domain;
                RegisterRequest req = request(email);
                assertTrue(factory.getValidator().validate(req).isEmpty(), "valid " + length);
                assertNotNull(service.register(req));
                assertEquals(email, saved.get().getEmail());
                assertNull(saved.get().getNickname(), "unset nickname falls back to the full email");
            }
            RegisterRequest astral = request(repeat('a', 49) + "\uD83D\uDE00@example.invalid");
            service.register(astral);
            assertNull(saved.get().getNickname());
            assertEquals(astral.getEmail(), saved.get().getEmail());
            RegisterRequest tooLong = request(repeat('a', 61) + "@" + repeat('b', 60) + ".c.test");
            assertEquals(129, tooLong.getEmail().length());
            assertFalse(factory.getValidator().validate(tooLong).isEmpty());
            assertThrows(BusinessException.class, () -> service.register(tooLong));
            verify(captcha, times(5)).verifyAndConsume(any(), any(), any());
        }
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    private static RegisterRequest request(String email) {
        RegisterRequest req = new RegisterRequest();
        req.setEmail(email);
        req.setPassword("secret123");
        req.setConfirmPassword("secret123");
        req.setCaptchaSession("0123456789abcdef0123456789abcdef");
        req.setCaptchaId("0123456789abcdef0123456789abcdef");
        req.setCaptchaCode("A2B3");
        return req;
    }
}
