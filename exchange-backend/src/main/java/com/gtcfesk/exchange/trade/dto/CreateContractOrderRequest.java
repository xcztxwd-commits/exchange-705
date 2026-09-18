package com.gtcfesk.exchange.trade.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreateContractOrderRequest {
    private String symbol; // 交易对
    private String side; // BUY 买入, SELL 卖出
    private String type; // MARKET 市价, LIMIT 限价
    private BigDecimal quantity; // 数量
    private BigDecimal price; // 挂单价格（限价单）
    private BigDecimal currentPrice; // 当前价格（市价单）
    private BigDecimal stopLoss; // 止损
    private BigDecimal takeProfit; // 止盈
}



