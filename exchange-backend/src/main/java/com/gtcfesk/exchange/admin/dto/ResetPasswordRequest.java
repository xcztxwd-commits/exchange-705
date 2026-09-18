package com.gtcfesk.exchange.admin.dto;

import lombok.Data;

@Data
public class ResetPasswordRequest {
    private Long userId;
    private String newPassword;
}







