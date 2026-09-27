package com.gtcfesk.exchange.user;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Existing whole-used-day/free-day repayment convention, without a balance mutation. */
public final class LoanInterest {
    private LoanInterest() {}
    public static BigDecimal accrued(BigDecimal amount, BigDecimal dailyRate, int freeDays, long actualDays) {
        long chargeableDays = Math.max(0, actualDays - freeDays);
        return amount.multiply(dailyRate.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP))
                .multiply(BigDecimal.valueOf(chargeableDays)).setScale(16, RoundingMode.HALF_UP);
    }
}
