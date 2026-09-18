package com.gtcfesk.exchange.admin.dto;

import lombok.Data;

@Data
public class UpdateUserStatusRequest {
    private Long userId;
    private String status; // normal, frozen, banned
}







