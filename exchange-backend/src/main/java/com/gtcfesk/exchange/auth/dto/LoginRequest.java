package com.gtcfesk.exchange.auth.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class LoginRequest {
    @NotBlank
    private String account;

    @NotBlank
    private String password;

    private String loginType; // email / phone
    private String countryCode;
}







