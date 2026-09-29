package com.gtcfesk.exchange.common;

/** Business denial, not an expired login. */
public class KycRequiredException extends BusinessException {
    private final String kycStatus;
    public KycRequiredException(String status) {
        super(403, "PENDING".equals(status) ? "实名认证审核中，通过后才可交易" :
                "REJECTED".equals(status) ? "实名认证未通过，请修改资料后重新提交" : "请先实名认证，审核通过后才可交易");
        this.kycStatus = status;
    }
    public String getKycStatus() { return kycStatus; }
}
