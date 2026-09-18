package com.gtcfesk.exchange.auth.dto;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class RegisterRequest {

    @Email
    @NotBlank
    private String email;

    @NotBlank
    @Size(min = 6, message = "password length must be >= 6")
    private String password;

    @NotBlank
    private String confirmPassword;

    @NotBlank
    private String verifyCode;

    private String invitationCode;
}

