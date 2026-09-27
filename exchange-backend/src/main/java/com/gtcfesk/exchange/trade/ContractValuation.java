package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.entity.ContractOrder;
import java.math.BigDecimal;

/** Pure arithmetic shared by settlement and equity; callers validate quote availability. */
public final class ContractValuation {
    private ContractValuation() {}
    public static BigDecimal quoteProfit(ContractOrder order, BigDecimal currentPrice) {
        if (order.getOpenPrice() == null || currentPrice == null
                || order.getOpenPrice().signum() <= 0 || currentPrice.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal multiplier = order.getLotSize() != null ? order.getLotSize()
                : (order.getLeverage() != null ? order.getLeverage() : BigDecimal.ONE);
        BigDecimal difference = "BUY".equals(order.getSide()) ? currentPrice.subtract(order.getOpenPrice())
                : order.getOpenPrice().subtract(currentPrice);
        return difference.multiply(order.getQuantity()).multiply(multiplier);
    }
}
