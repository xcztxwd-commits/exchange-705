package com.gtcfesk.exchange.common;
import java.math.BigDecimal;
public final class TradeValidation {
    private TradeValidation() { }
    public static void positive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0 || value.scale() > 16 || value.precision() - value.scale() > 16) {
            throw new BusinessException(name + "必须为有效正数（最多16位整数和16位小数）");
        }
    }
    public static void nonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0 || value.scale() > 16 || value.precision() - value.scale() > 16) {
            throw new BusinessException(name + "必须为有效非负数（最多16位整数和16位小数）");
        }
    }
    public static void contractLots(BigDecimal value) {
        positive(value,"合约手数");
        if(value.compareTo(new BigDecimal("0.01"))<0 || value.stripTrailingZeros().scale()>2)
            throw new BusinessException("合约数量必须不小于0.01手，且为0.01手的整数倍");
    }
    public static void optionalPositive(BigDecimal value, String name) {
        if (value != null) positive(value, name);
    }
    public static void leverage(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ONE) < 0
                || value.compareTo(BigDecimal.valueOf(100)) > 0 || value.stripTrailingZeros().scale() > 0) {
            throw new BusinessException("杠杆倍数必须为1至100的整数");
        }
    }
}
