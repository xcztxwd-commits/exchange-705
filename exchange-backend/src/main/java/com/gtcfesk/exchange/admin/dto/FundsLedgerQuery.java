package com.gtcfesk.exchange.admin.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import javax.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class FundsLedgerQuery {
    @Min(0) private int page;
    @Min(1) @Max(100) private int size = 25;
    @Size(max = 254) private String keyword;
    @Positive private Long userId;
    @Positive private Long filterAgentId;
    private String accountType;
    private String type;
    private String direction;
    private String currency;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private LocalDateTime startTime;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) private LocalDateTime endTime;
    @DecimalMin("0") @Digits(integer = 16, fraction = 16) private BigDecimal amountMin;
    @DecimalMin("0") @Digits(integer = 16, fraction = 16) private BigDecimal amountMax;
}
