package com.gtcfesk.exchange.security;

public class SecurityFailure extends RuntimeException {
    public final int status;
    public final String code;
    public final long retryAfter;
    public SecurityFailure(int status, String code, long retryAfter) {
        super(code); this.status = status; this.code = code; this.retryAfter = retryAfter;
    }
    public static SecurityFailure unavailable() { return new SecurityFailure(503, "SECURITY_UNAVAILABLE", 0); }
    public static SecurityFailure invalid() { return new SecurityFailure(400, "CAPTCHA_INVALID", 0); }
}
