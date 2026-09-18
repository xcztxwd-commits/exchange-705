package com.gtcfesk.exchange.auth.dto;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class ResetPasswordRequest {
    @Email
    @NotBlank
    private String email;

    @NotBlank
    private String verifyCode;

    @NotBlank
    @Size(min = 6, message = "password length must be >= 6")
    private String password;

    @NotBlank
    private String confirmPassword;
    
    // 场景：forget_password 或 change_password
    private String scene;
}

