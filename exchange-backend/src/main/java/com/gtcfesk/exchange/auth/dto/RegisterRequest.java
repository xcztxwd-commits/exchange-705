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

    // Registration email verification is temporarily disabled.
    private String verifyCode;

    @Size(max = 100)
    private String invitationCode;

    @NotBlank
    @javax.validation.constraints.Pattern(regexp = "[a-f0-9]{32}")
    private String captchaSession;

    @NotBlank
    @javax.validation.constraints.Pattern(regexp = "[a-f0-9]{32}")
    private String captchaId;

    @NotBlank
    @javax.validation.constraints.Pattern(regexp = "[A-Za-z0-9]{4}")
    private String captchaCode;
}

