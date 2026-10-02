package com.gtcfesk.exchange.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.support.SupportService;
import java.math.BigDecimal;
import java.util.Arrays;

/** Durable creation receipts live in the business order and commit with its funds effect. */
public final class OrderRequest {
    private OrderRequest() {}
    public static String required(Object key) {
        if (!(key instanceof String) || !((String)key).matches("[A-Za-z0-9_-]{16,64}"))
            throw new BusinessException("请求编号必须为16至64位字母、数字、下划线或连字符");
        return (String)key;
    }
    // Only legacy internal callers may omit a key; all public creation controllers require it.
    public static String optional(String key) { return key == null ? null : required(key); }
    public static String hash(Object... fields) {
        Object[] normalized=fields.clone();
        for(int i=0;i<normalized.length;i++) if(normalized[i] instanceof BigDecimal)
            normalized[i]=((BigDecimal)normalized[i]).stripTrailingZeros().toString();
        try { return SupportService.sha256(new ObjectMapper().writeValueAsBytes(Arrays.asList(normalized))); }
        catch(java.io.IOException e) { throw new IllegalStateException("Cannot serialize order request",e); }
    }
    public static void same(String expected,String actual) {
        if (!java.util.Objects.equals(expected,actual)) throw new BusinessException("请求编号已用于不同内容，请核对原订单");
    }
    public static String source(String supplied,String fallback) { return supplied==null||supplied.isEmpty()?fallback:supplied; }
}
