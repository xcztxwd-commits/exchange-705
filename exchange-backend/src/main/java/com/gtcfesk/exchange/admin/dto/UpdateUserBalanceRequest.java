package com.gtcfesk.exchange.admin.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateUserBalanceRequest {
    private Long userId;
    private String currency;
    private String account;
    private BigDecimal amount;

    /** 资金账户余额 */
    private BigDecimal fundBalance;

    /** 合约资产余额 */
    private BigDecimal contractBalance;

    /** 期权账户余额 */
    private BigDecimal optionBalance;
}




