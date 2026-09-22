package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.TradeValidation;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class FiatCurrencyService {
    public static final List<String> CURRENCIES = java.util.Collections.unmodifiableList(
            Arrays.asList("USD", "EUR", "JPY", "GBP", "AUD", "CAD", "SGD", "CNY"));
    private final ForexQuoteMarketService market;

    public String currency(String value) {
        String code = value == null ? "USD" : value.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(code)) throw new BusinessException("不支持的币种");
        return code;
    }

    public BigDecimal rate(String currency) {
        BigDecimal rate = market.requireConversionRate(currency(currency), "yahoo");
        if (rate.signum() <= 0) throw new BusinessException("汇率暂不可用，请稍后重试");
        return rate;
    }

    public BigDecimal toUsd(BigDecimal amount, BigDecimal rate) {
        TradeValidation.positive(amount, "充值金额");
        BigDecimal usd = amount.multiply(rate).setScale(16, RoundingMode.HALF_UP);
        TradeValidation.positive(usd, "折合美元金额");
        return usd;
    }
}
