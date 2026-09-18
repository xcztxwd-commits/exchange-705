package com.gtcfesk.exchange.admin.dto;

import lombok.Data;

@Data
public class UpdateUserTypeRequest {
    private Long userId;
    private String userType; // normal, agent
}

