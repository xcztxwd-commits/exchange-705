package com.gtcfesk.exchange.admin.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class UpdateTrialAccountRequest {
 private Boolean enabled;
 private BigDecimal available;
 private Long expectedVersion;
 private String requestId;
 private String reason;
 private boolean confirm;
}
