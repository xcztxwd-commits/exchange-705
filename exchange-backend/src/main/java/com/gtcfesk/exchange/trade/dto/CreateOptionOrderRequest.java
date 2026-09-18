package com.gtcfesk.exchange.trade.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreateOptionOrderRequest {
    private String symbol; // 交易对
    private String direction; // UP 买涨, DOWN 买跌
    private BigDecimal amount; // 金额
    private BigDecimal currentPrice; // 当前价格
    private Integer duration; // 时长（秒）
}



