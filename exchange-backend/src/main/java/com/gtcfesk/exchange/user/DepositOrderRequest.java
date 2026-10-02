package com.gtcfesk.exchange.user;
import java.math.BigDecimal;
/** Explicit input allowlist: audit and credited amounts are never client controlled. */
public class DepositOrderRequest {
 public Long userId;
 public String account="FUND", currency="USD", type="manual", manualPurpose="ADJUSTMENT";
 public String remark, proofImage, address="", network="MANUAL", idempotencyKey;
 public BigDecimal amount;
 @com.fasterxml.jackson.annotation.JsonSetter("feeRate")
 public void validateFee(BigDecimal value) { if(value!=null && value.signum()!=0)throw new IllegalArgumentException("第一版仅支持零费率"); }

}
