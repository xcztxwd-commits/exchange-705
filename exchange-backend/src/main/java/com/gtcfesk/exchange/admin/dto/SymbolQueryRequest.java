package com.gtcfesk.exchange.admin.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SymbolQueryRequest {
    private String category; // US, Crypto, Metal, Forex, CFD, Oil
    private Integer page = 0;
    private Integer size = 20;
}

