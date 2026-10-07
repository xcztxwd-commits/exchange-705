package com.gtcfesk.exchange.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.auth.dto.RegisterRequest;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.UserAccount;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** Only the two optional profile fields are configurable; credentials and captcha are not. */
public final class RegistrationFields {
    public static final String KEY = "registration.fields";
    public static final BigDecimal MAX_INCOME = new BigDecimal("999999999999.99");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Field phone;
    private final Field annualIncome;

    public static final class Field {
        private final boolean enabled, required;
        public Field(boolean enabled, boolean required) {
            if (required && !enabled) throw invalid();
            this.enabled = enabled;
            this.required = required;
        }
        public boolean isEnabled() { return enabled; }
        public boolean isRequired() { return required; }
    }
    private RegistrationFields(Field phone, Field annualIncome) {
        this.phone = phone;
        this.annualIncome = annualIncome;
    }
    public Field getPhone() { return phone; }
    public Field getAnnualIncome() { return annualIncome; }
    public static RegistrationFields defaults() {
        return new RegistrationFields(new Field(true, false), new Field(true, false));
    }
    private static BusinessException invalid() { return new BusinessException("注册字段配置无效：必填字段必须开启"); }

    public static RegistrationFields parse(String text) {
        if (text == null) return defaults();
        try {
            if (text.length() > 1024) throw invalid();
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject() || root.size() != 2 || !root.has("phone") || !root.has("annualIncome")) throw invalid();
            return new RegistrationFields(field(root.get("phone")), field(root.get("annualIncome")));
        } catch (java.io.IOException e) { throw invalid(); }
    }
    private static Field field(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 2 || !node.path("enabled").isBoolean() || !node.path("required").isBoolean()) throw invalid();
        return new Field(node.path("enabled").asBoolean(), node.path("required").asBoolean());
    }
    private static final List<String> CURRENCIES = Arrays.stream(Locale.getISOCountries())
        .map(country -> {
            try { return Currency.getInstance(new Locale("", country)); }
            catch (IllegalArgumentException unknown) { return null; }
        })
        .filter(Objects::nonNull).filter(c -> c.getDefaultFractionDigits() >= 0)
        .map(Currency::getCurrencyCode).distinct().sorted().collect(Collectors.toList());
    public static List<String> currencies() {
        return Collections.unmodifiableList(Arrays.asList("USD", "EUR", "JPY", "GBP", "CNY", "CHF", "AUD", "CAD"));
    }
    public void apply(RegisterRequest req, UserAccount user) {
        String dial = blank(req.getCountryCode());
        String number = blank(req.getPhone());
        if (!phone.enabled) {
            if (dial != null || number != null) throw new BusinessException("手机号字段已关闭");
        } else if (dial == null || number == null) {
            if (phone.required || dial != null || number != null) throw new BusinessException("请填写国际区号和手机号");
        } else {
            if (dial.matches("[1-9][0-9]{0,2}")) dial = "+" + dial;
            if (!dial.matches("[+][1-9][0-9]{0,2}") || !number.matches("[+0-9 ()-]{4,32}")) throw new BusinessException("手机号格式无效");
            number = number.replaceAll("[ ()-]", "");
            if (number.startsWith("+")) {
                if (!number.startsWith(dial)) throw new BusinessException("手机号与国际区号不一致");
                number = number.substring(dial.length());
            }
            if (!number.matches("[0-9]{4,14}") || (dial.length() - 1 + number.length()) > 15 || (dial.length() - 1 + number.length()) < 7)
                throw new BusinessException("手机号格式无效");
            user.setCountryCode(dial);
            user.setPhone(number);
        }

        BigDecimal income = req.getAnnualIncome();
        String currency = blank(req.getAnnualIncomeCurrency());
        if (!annualIncome.enabled) {
            if (income != null || currency != null) throw new BusinessException("年收入字段已关闭");
        } else if (income == null || currency == null) {
            if (annualIncome.required || income != null || currency != null) throw new BusinessException("请填写年收入和币种");
        } else {
            if (income.signum() < 0 || income.scale() > 2 || income.compareTo(MAX_INCOME) > 0)
                throw new BusinessException("年收入金额无效（最多两位小数）");
            currency = currency.toUpperCase(Locale.ROOT);
            if (!currency.matches("[A-Z]{3}") || !CURRENCIES.contains(currency)) throw new BusinessException("年收入币种无效");
            user.setAnnualIncome(income);
            user.setAnnualIncomeCurrency(currency);
        }
    }
    private static String blank(String s) { return s == null || s.trim().isEmpty() ? null : s.trim(); }
}
