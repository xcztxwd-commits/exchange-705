package com.gtcfesk.exchange.common;
public final class SafeErrors {
    private SafeErrors() { }
    public static String message(Exception e) {
        if (e instanceof BusinessException) return e.getMessage();
        if (e instanceof org.springframework.security.access.AccessDeniedException) return "无权执行此操作";
        if (e instanceof org.springframework.dao.OptimisticLockingFailureException || e instanceof javax.persistence.OptimisticLockException) return "数据已变更，请刷新后重试";
        if (e instanceof IllegalArgumentException || e instanceof NullPointerException) return "请求参数无效或不完整";
        return "操作失败，请稍后重试";
    }
}
