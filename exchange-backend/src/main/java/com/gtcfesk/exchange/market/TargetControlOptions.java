package com.gtcfesk.exchange.market;

import lombok.Getter;
import lombok.Setter;
import javax.validation.constraints.*;
import java.math.BigDecimal;

/** User-facing V4 inputs; precision and all algorithm coefficients remain server-owned. */
@Getter @Setter
public class TargetControlOptions extends RecoveryOptions {
    @Pattern(regexp = "AUTO|MANUAL") private String deviationBandMode = "AUTO";
    @DecimalMin(value = "0", inclusive = false) @DecimalMax("100")
    @Digits(integer = 3, fraction = 8) private BigDecimal deviationBandPercent;
    @Size(min = 1, max = 256) private String stepFormula;
}
