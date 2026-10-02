package com.gtcfesk.exchange.market;

import lombok.Getter;
import lombok.Setter;
import javax.validation.constraints.*;
import java.util.*;

/** Immutable JSON snapshot is stored at task creation; setters are for request binding only. */
@Getter @Setter
public class RecoveryOptions {
    @NotNull private Boolean autoRestore = false;
    @NotNull @Pattern(regexp = "GRADUAL|QUICK") private String restoreMode = "GRADUAL";
    @NotNull @Min(1) @Max(86400) private Integer restoreDurationSeconds = 10;
    @NotNull @Min(1) @Max(10) private Integer restoreIntensity = 5;
    @NotNull private Boolean restoreRandomOscillation = true;
    @NotNull private Boolean autoReplaceHistory = true;
    Map<String,Object> snapshot() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("autoRestore", autoRestore); m.put("restoreMode", restoreMode);
        m.put("restoreDurationSeconds", restoreDurationSeconds); m.put("restoreIntensity", restoreIntensity);
        m.put("restoreRandomOscillation", restoreRandomOscillation); m.put("autoReplaceHistory", autoReplaceHistory);
        return m;
    }
}
