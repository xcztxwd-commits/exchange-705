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
    private BigDecimal leverage; // 用户选择的杠杆；省略时默认100倍（受品种上限限制）
    private BigDecimal price; // 挂单价格（限价单）
    private BigDecimal currentPrice; // 兼容旧客户端，服务端不采用此值作为行情或成交价
    private BigDecimal stopLoss; // 止损
    private BigDecimal takeProfit; // 止盈
}



