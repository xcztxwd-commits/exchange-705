package com.gtcfesk.exchange.common;
import java.math.BigDecimal;
public final class TradeValidation {
    private TradeValidation() { }
    public static void positive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0 || value.scale() > 16 || value.precision() - value.scale() > 16) {
            throw new BusinessException(name + "必须为有效正数（最多16位整数和16位小数）");
        }
    }
    public static void optionalPositive(BigDecimal value, String name) {
        if (value != null) positive(value, name);
    }
}
