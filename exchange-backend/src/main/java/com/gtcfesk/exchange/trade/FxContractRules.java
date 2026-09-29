package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.BigDecimal;

/** USD-settled standard FX lots. feeMultiplier remains the reserved ROUND-TRIP USD fee. */
public final class FxContractRules {
    private FxContractRules() {}
    public static boolean isForex(TradingSymbol symbol) {
        return "Forex".equalsIgnoreCase(symbol.getSourceCategory());
    }
    public static void defaults(TradingSymbol symbol) {
        if (!isForex(symbol)) return;
        symbol.setLotSize(new BigDecimal("100000"));
        symbol.setMinTradeAmount(new BigDecimal("0.01"));
        symbol.setVolumePrecision(2);
        symbol.setFeeMultiplier(new BigDecimal("7.00"));
    }
    public static void validate(TradingSymbol symbol) {
        if (!isForex(symbol)) return;
        if (symbol.getLotSize() == null || symbol.getLotSize().compareTo(new BigDecimal("100000")) != 0
                || symbol.getBaseCurrency() == null || !symbol.getBaseCurrency().matches("[A-Z]{3}"))
            throw new BusinessException("外汇标准手必须为100000基础货币单位，请检查品种配置");
        if (symbol.getFeeMultiplier() == null || symbol.getFeeMultiplier().signum() < 0)
            throw new BusinessException("外汇往返佣金配置无效");
    }
    public static void quantity(BigDecimal value) {
        com.gtcfesk.exchange.common.TradeValidation.contractLots(value);
    }
}
