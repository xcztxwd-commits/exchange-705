package com.gtcfesk.exchange.simulation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Fixed read projections: never expose credentials, identity documents or arbitrary SQL. */
@Service @RequiredArgsConstructor
public class AccountInspection {
    private final JdbcTemplate jdbc;
    static final Map<String,String[]> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("users", new String[]{"user_account","users","id,email,remark,nickname,status,kyc_status,created_at","id"});
        TYPES.put("wallets", new String[]{"asset_account","users","id,user_id,coin,available,frozen,updated_at","user_id"});
        TYPES.put("contracts", new String[]{"contract_order","orders","id,user_id,symbol,side,type,quantity,open_price,close_price,status,profit,margin,fee,created_at","user_id"});
        TYPES.put("options", new String[]{"option_order","orders","id,user_id,symbol,direction,amount,open_price,close_price,status,profit,duration,created_at","user_id"});
        TYPES.put("deposits", new String[]{"deposit_record","deposit_orders","id,user_id,amount,currency,status,account_type,created_at","user_id"});
        TYPES.put("withdrawals", new String[]{"withdraw_record","withdraw_review","id,user_id,amount,actual_amount,fee,currency,status,created_at","user_id"});
        TYPES.put("transfers", new String[]{"transfer_record","users","id,user_id,from_account,to_account,amount,created_at","user_id"});
        TYPES.put("loans", new String[]{"loan_record","loan_review","id,user_id,amount,days,total_interest,overdue_fee,repayment_amount,status,contract_signed,repayment_date,actual_repayment_at,created_at","user_id"});
        TYPES.put("financial", new String[]{"financial_order","financial_orders","id,user_id,product_name,purchase_amount,currency,daily_yield,total_yield,status,purchase_time,end_time,redeem_time","user_id"});
        TYPES.put("equity", new String[]{"asset_snapshot","users","id,user_id,total,captured_at","user_id"});
    }
    public static String[] type(String key) {
        String[] spec=TYPES.get(key);
        if(spec==null) throw new IllegalArgumentException("不支持的数据分类");
        return spec;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> read(String kind, Long userId, String status, int page, int size) {
        return read(kind,userId,null,status,page,size);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> read(String kind, Long userId, String userEmail, String status, int page, int size) {
        String[] spec=type(kind);
        if(page<1 || page>100000 || size<1 || size>100 || (userId!=null && userId<=0)) throw new IllegalArgumentException("筛选参数无效");
        boolean users = "users".equals(kind);
        String from = spec[0]+" x"+(users ? "" : " LEFT JOIN user_account u ON u.tenant_id=x.tenant_id AND u.id=x."+spec[3]);
        String where=" WHERE x.tenant_id=?"; List<Object> args=new ArrayList<>(); args.add(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        String emailPattern=com.gtcfesk.exchange.admin.AdminUserIdentity.emailPattern(userEmail);
        if(emailPattern!=null){where+=" AND LOWER("+(users?"x":"u")+".email) LIKE ? ESCAPE '!'";args.add(emailPattern);}
        if(userId!=null){where+=" AND x."+spec[3]+"=?";args.add(userId);}
        if(status!=null && !status.isEmpty()) {
            if(!Arrays.asList(spec[2].split(",")).contains("status") || status.length()>32) throw new IllegalArgumentException("此分类不支持该状态筛选");
            where+=" AND x.status=?";args.add(status);
        }
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM "+from+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        String select="x."+spec[2].replace(",",",x.");
        if(!users)select+=",u.email AS user_email,u.remark AS user_remark";
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT "+select+" FROM "+from+where+" ORDER BY x.id DESC LIMIT ? OFFSET ?",args.toArray());
        Map<String,Object> out=new LinkedHashMap<>();out.put("rows",rows);out.put("total",total);out.put("columns",(spec[2]+(users?"":",user_email,user_remark")).split(","));out.put("page",page);out.put("size",size);return out;
    }
}
