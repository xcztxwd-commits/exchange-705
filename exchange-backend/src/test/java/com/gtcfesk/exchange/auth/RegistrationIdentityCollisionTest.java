package com.gtcfesk.exchange.auth;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.auth.dto.RegisterRequest;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.config.GlobalExceptionHandler;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.security.RegistrationSecurity;
import com.gtcfesk.exchange.service.EmailService;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.sql.SQLException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RegistrationIdentityCollisionTest {
    private UserAccountRepository users;
    private AssetAccountRepository assets;
    private RegistrationSecurity captcha;
    private JwtUtil jwt;
    private AuthService service;
    private static final String PHONE="2025550198";
    @BeforeEach void setup() {
        TenantContext.clear();users=mock(UserAccountRepository.class);assets=mock(AssetAccountRepository.class);
        captcha=mock(RegistrationSecurity.class);jwt=mock(JwtUtil.class);
        PasswordEncoder encoder=mock(PasswordEncoder.class);when(encoder.encode(anyString())).thenReturn("test-hash");
        SystemConfigService configs=mock(SystemConfigService.class);when(configs.registrationFields()).thenReturn(RegistrationFields.defaults());
        service=new AuthService(mock(VerifyCodeRepository.class),mock(EmailService.class),users,encoder,assets);
        ReflectionTestUtils.setField(service,"registrationSecurity",captcha);ReflectionTestUtils.setField(service,"jwtUtil",jwt);
        ReflectionTestUtils.setField(service,"systemConfigService",configs);ReflectionTestUtils.setField(service,"tenantPolicy",mock(com.gtcfesk.exchange.control.TenantPolicyService.class));
        when(jwt.getExpireSeconds()).thenReturn(3600L);
        when(users.saveAndFlush(any())).thenAnswer(call->{UserAccount user=call.getArgument(0);user.setTenantId(TenantContext.requireTenantId());user.setId(100L+TenantContext.requireTenantId());return user;});
    }
    @AfterEach void cleanup(){TenantContext.clear();}
    private RegisterRequest request(String email) {
        RegisterRequest req=new RegisterRequest();req.setEmail(email);req.setPassword("test-password-123");req.setConfirmPassword(req.getPassword());
        req.setCountryCode("+65");req.setPhone("+65 (202) 555-0198");req.setCaptchaSession("0123456789abcdef0123456789abcdef");req.setCaptchaId(req.getCaptchaSession());req.setCaptchaCode("A2B3");return req;
    }
    @Test void sameTenantNormalizedPhoneIsRejectedAsHttp400BeforePersistence() throws Exception {
        try(TenantContext.Scope ignored=TenantContext.open(1L)) {
            UserAccount existing=new UserAccount();existing.setTenantId(1L);existing.setPhone(PHONE);
            when(users.findByTenantIdAndPhone(1L,PHONE)).thenReturn(Optional.of(existing));
            MockMvcBuilders.standaloneSetup(new AuthController(service,captcha)).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(post("/api/auth/register").contentType("application/json").content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request("new@example.test"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("phone exists"));
            verify(users).findByTenantIdAndPhone(1L,PHONE);verify(users,never()).saveAndFlush(any());verify(users,never()).save(any());verifyNoInteractions(assets,jwt);
        }
    }
    @Test void clearMySqlDuplicateRaceIsControlledAndKeepsRollbackContract() throws Exception {
        try(TenantContext.Scope ignored=TenantContext.open(1L)) {
            DataIntegrityViolationException conflict=new DataIntegrityViolationException("private SQL details",new SQLException("private duplicate value","23000",1062));
            doThrow(conflict).when(users).saveAndFlush(any());
            BusinessException denied=assertThrows(BusinessException.class,()->service.register(request("race@example.test")));
            assertEquals("registration identity exists",denied.getMessage());assertSame(conflict,denied.getCause());
            org.springframework.transaction.interceptor.TransactionAttribute transaction=new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()
                .getTransactionAttribute(AuthService.class.getMethod("register",RegisterRequest.class),AuthService.class);
            assertNotNull(transaction);assertTrue(transaction.rollbackOn(denied));verifyNoInteractions(assets,jwt);verify(users,never()).save(any());
        }
    }
    @Test void knownNormalizedIdentityConstraintsAreControlledWithoutParsingPrivateMessages() {
        try(TenantContext.Scope ignored=TenantContext.open(1L)) {
            for(String index:new String[]{"uk_tenant_normalized_email","uk_tenant_normalized_phone"}) {
                DataIntegrityViolationException conflict=new DataIntegrityViolationException("not public",new org.hibernate.exception.ConstraintViolationException("not public",new SQLException("not public","23505"),index));
                doThrow(conflict).when(users).saveAndFlush(any());
                assertSame(conflict,assertThrows(BusinessException.class,()->service.register(request("known@example.test"))).getCause());
            }
            verifyNoInteractions(assets,jwt);
        }
    }
    @Test void foreignKeyAndOtherDatabaseIntegrityErrorsAreNotDisguisedAsDuplicates() {
        try(TenantContext.Scope ignored=TenantContext.open(1L)) {
            for(int code:new int[]{1452,1048,0}) {
                DataIntegrityViolationException failure=new DataIntegrityViolationException("unrelated failure",new SQLException("unrelated failure","23000",code));
                doThrow(failure).when(users).saveAndFlush(any());
                assertSame(failure,assertThrows(DataIntegrityViolationException.class,()->service.register(request("other@example.test"))));
            }
            DataIntegrityViolationException foreign=new DataIntegrityViolationException("unrelated constraint",new org.hibernate.exception.ConstraintViolationException("unrelated",new SQLException("unrelated","23000",1452),"fk_unrelated"));
            doThrow(foreign).when(users).saveAndFlush(any());assertSame(foreign,assertThrows(DataIntegrityViolationException.class,()->service.register(request("fk@example.test"))));verifyNoInteractions(assets,jwt);
        }
    }
    @Test void sameNormalizedPhoneInDifferentTenantIsAllowedAndQueryScopeRemainsServerBound() {
        try(TenantContext.Scope ignored=TenantContext.open(1L)) {
            UserAccount existing=new UserAccount();existing.setTenantId(1L);existing.setPhone(PHONE);
            when(users.findByTenantIdAndPhone(1L,PHONE)).thenReturn(Optional.of(existing));
        }
        try(TenantContext.Scope ignored=TenantContext.open(2L)) {
            assertNotNull(service.register(request("same@example.test")));verify(users).findByTenantIdAndPhone(2L,PHONE);
            verify(users,never()).findByTenantIdAndPhone(1L,PHONE);verify(assets,times(3)).save(any());
        }
    }
    @Test void optionalPhoneRemainsAbsentAndDoesNotQueryForNullIdentity() {
        try(TenantContext.Scope ignored=TenantContext.open(1L)) {
            RegisterRequest req=request("optional@example.test");req.setPhone(null);req.setCountryCode(null);
            assertNotNull(service.register(req));verify(users,never()).findByTenantIdAndPhone(anyLong(),any());verify(assets,times(3)).save(any());
        }
    }
}
