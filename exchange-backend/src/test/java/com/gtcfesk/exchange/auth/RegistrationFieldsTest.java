package com.gtcfesk.exchange.auth;

import com.gtcfesk.exchange.auth.dto.RegisterRequest;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.SystemConfig;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.SystemConfigRepository;
import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationFieldsTest {
    private static final String BOTH_ON = "{\"phone\":{\"enabled\":true,\"required\":true},\"annualIncome\":{\"enabled\":true,\"required\":true}}";
    private static final String BOTH_OFF = "{\"phone\":{\"enabled\":false,\"required\":false},\"annualIncome\":{\"enabled\":false,\"required\":false}}";
    private RegisterRequest request(String dial, String phone, String amount, String currency) {
        RegisterRequest req = new RegisterRequest();
        req.setCountryCode(dial); req.setPhone(phone);
        req.setAnnualIncome(amount == null ? null : new BigDecimal(amount));
        req.setAnnualIncomeCurrency(currency);
        return req;
    }
    @Test void fourFieldModesAndMalformedConfig() {
        assertTrue(RegistrationFields.defaults().getPhone().isEnabled());
        assertTrue(RegistrationFields.currencies().contains("USD"));
        assertTrue(RegistrationFields.currencies().contains("SGD"));
        assertFalse(RegistrationFields.currencies().contains("ADP"));
        assertFalse(RegistrationFields.defaults().getPhone().isRequired());
        assertFalse(RegistrationFields.parse(BOTH_OFF).getPhone().isEnabled());
        assertTrue(RegistrationFields.parse(BOTH_ON).getAnnualIncome().isRequired());
        assertTrue(RegistrationFields.parse("{\"phone\":{\"enabled\":true,\"required\":false},\"annualIncome\":{\"enabled\":false,\"required\":false}}").getPhone().isEnabled());
        for (String invalid : new String[] {
            "{\"phone\":{\"enabled\":false,\"required\":true},\"annualIncome\":{\"enabled\":true,\"required\":false}}",
            "{\"phone\":{\"enabled\":true,\"required\":false},\"annualIncome\":{\"enabled\":false,\"required\":true}}",
            "{\"phone\":{\"enabled\":\"true\",\"required\":false},\"annualIncome\":{\"enabled\":true,\"required\":false}}",
            "{}" }) assertThrows(BusinessException.class, () -> RegistrationFields.parse(invalid));
        assertThrows(BusinessException.class, () -> RegistrationFields.parse(BOTH_OFF).apply(request("+65", "81234567", null, null), new UserAccount()));
        assertThrows(BusinessException.class, () -> RegistrationFields.parse(BOTH_ON).apply(request(null, null, "12", "USD"), new UserAccount()));
        assertThrows(BusinessException.class, () -> RegistrationFields.parse(BOTH_ON).apply(request("+65", "81234567", null, null), new UserAccount()));
    }
    @Test void phoneAndIncomeValidationAndLegacyEmptyFields() {
        UserAccount old = new UserAccount();
        RegistrationFields.defaults().apply(request(null, null, null, null), old);
        assertNull(old.getPhone()); assertNull(old.getAnnualIncome());
        for (String[] pair : new String[][] { {"+0","81234567"}, {"+1234","81234567"}, {"+65","1a234567"}, {"+65","12"}, {"+65","+4481234567"} })
            assertThrows(BusinessException.class, () -> RegistrationFields.defaults().apply(request(pair[0], pair[1], null, null), new UserAccount()));
        for (String amount : new String[] {"-1", "1.234", "1000000000000", "999999999999.991"})
            assertThrows(BusinessException.class, () -> RegistrationFields.defaults().apply(request(null, null, amount, "USD"), new UserAccount()));
        for (String currency : new String[] {"XXX", "XAU", "ZZZ"})
            assertThrows(BusinessException.class, () -> RegistrationFields.defaults().apply(request(null, null, "1.00", currency), new UserAccount()));
        assertThrows(BusinessException.class, () -> RegistrationFields.defaults().apply(request(null, null, null, "USD"), new UserAccount()));
        UserAccount valid = new UserAccount();
        RegistrationFields.defaults().apply(request("65", "+65 8123-4567", "999999999999.99", "sgd"), valid);
        assertEquals("+65", valid.getCountryCode()); assertEquals("81234567", valid.getPhone());
        assertEquals(new BigDecimal("999999999999.99"), valid.getAnnualIncome());
        assertEquals("SGD", valid.getAnnualIncomeCurrency());
    }
    @Test void tenantEffectiveConfigAndSaveValidation() {
        SystemConfigRepository repo = mock(SystemConfigRepository.class);
        TenantPolicyService tenants = mock(TenantPolicyService.class);
        SystemConfigService service = new SystemConfigService();
        ReflectionTestUtils.setField(service, "systemConfigRepository", repo);
        ReflectionTestUtils.setField(service, "tenantPolicy", tenants);
        when(tenants.effectiveConfig(eq(RegistrationFields.KEY), any())).thenAnswer(i -> i.getArgument(1));
        SystemConfig first = new SystemConfig(); first.setConfigValue(BOTH_ON);
        SystemConfig second = new SystemConfig(); second.setConfigValue(BOTH_OFF);
        when(repo.findByTenantIdAndConfigKey(1L, RegistrationFields.KEY)).thenReturn(Optional.of(first));
        when(repo.findByTenantIdAndConfigKey(2L, RegistrationFields.KEY)).thenReturn(Optional.of(second));
        try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            assertTrue(service.registrationFields().getPhone().isRequired());
            assertThrows(BusinessException.class, () -> service.saveConfig(RegistrationFields.KEY,
                "{\"phone\":{\"enabled\":false,\"required\":true},\"annualIncome\":{\"enabled\":true,\"required\":false}}", null));
            verify(repo, never()).save(any());
        }
        try (TenantContext.Scope ignored = TenantContext.open(2L)) {
            assertFalse(service.registrationFields().getPhone().isEnabled());
        }
    }
}
