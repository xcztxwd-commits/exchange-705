package com.gtcfesk.exchange.auth.dto;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;

@Data
public class SendCodeRequest {
    @Email
    @NotBlank
    private String email;

    @NotBlank
    private String scene; // register / login / forget_password
}







