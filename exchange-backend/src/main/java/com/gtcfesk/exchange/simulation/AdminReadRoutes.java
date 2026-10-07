package com.gtcfesk.exchange.simulation;
import java.util.*;

/** No wildcard administrative access: every forwarded operation is a reviewed read. */
public final class AdminReadRoutes {
    public static String permission(String method,String path) {
        if (method == null || path == null) throw new IllegalArgumentException("method 和 path 必填");
        if ("POST".equals(method)) {
            if ("/api/admin/users/query".equals(path)) return "users";
            if (path.matches("/api/admin/orders/(contract|option)/query")) return "orders";
            if ("/api/admin/orders/contract/live".equals(path)) return "orders";
        }
        if ("GET".equals(method)) {
            if(path.matches("/api/admin/orders/(contract|option)/[1-9][0-9]*/share-preview"))return "orders";
            if(path.equals("/api/admin/users"))return "users";
            if(path.equals("/api/admin/statistics"))return "statistics";
            if(path.equals("/api/admin/support/inbox"))return "inbox";
            if(path.equals("/api/admin/activities"))return "announcement";
            if(path.matches("/api/admin/activities/[0-9]+/(stats|recipients)") || path.matches("/api/admin/activities/users/[0-9]+/(account|ledger)"))return "announcement:detail";
            if(path.matches("/api/admin/agents/[0-9]+/performance"))return "agents:performance";
            if(path.matches("/api/admin/users/[0-9]+")) return "users:detail";
            if(path.matches("/api/admin/users/[0-9]+/fund-details")) return "users:fund_details";
            if(path.matches("/api/admin/users/[0-9]+/subordinates")) return "users:view_subordinates";
            if(path.matches("/api/admin/wallet/[0-9]+/(bank-cards|digital-addresses)")) return "users:wallet_management";
            if(path.equals("/api/admin/deposit/review/list"))return "deposit_review";
            if(path.equals("/api/admin/withdraw/list"))return "withdraw_review";
            if(path.equals("/api/admin/loan/review/list"))return "loan_review";
            if(path.equals("/api/admin/loan/personal-info/list"))return "loan_personal_info_review";
            if(path.equals("/api/admin/kyc/list"))return "kyc_review";
            if(path.equals("/api/admin/financial/orders"))return "financial_orders";
            if(path.matches("/api/admin/financial/yield/order/[0-9]+"))return "financial_orders:detail";
            if(path.equals("/api/admin/deposit/orders/list") || path.equals("/api/admin/deposit/orders/summary") || path.equals("/api/admin/deposit/orders/customers"))return "deposit_orders:view_deposit_orders";
            if(path.matches("/api/admin/deposit/orders/[0-9]+"))return "deposit_orders:detail";
            if(path.equals("/api/admin/deposit/orders/export"))return "deposit_orders:export_deposit_orders";
        }
        throw new IllegalArgumentException("该接口不支持模拟账户只读查询");
    }
    public static class Query {
        public String method;
        public String path;
        public Map<String,Object> params = new LinkedHashMap<>();
        public Map<String,Object> body = new LinkedHashMap<>();
    }
}
