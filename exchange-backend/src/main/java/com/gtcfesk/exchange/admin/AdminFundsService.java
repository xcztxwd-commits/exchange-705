package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.admin.dto.FundsLedgerQuery;
import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

/** Read-only projection of committed accounting sources. Never reconstruct unknown historical balances. */
@Service
@RequiredArgsConstructor
public class AdminFundsService {
    private final JdbcTemplate jdbc;
    private static final Map<String, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("DEPOSIT", "充值入账"); TYPES.put("WITHDRAW", "提现出账");
        TYPES.put("ADMIN_IN", "后台入金"); TYPES.put("ADMIN_OUT", "后台出金");
        TYPES.put("CONTRACT_PNL", "合约盈亏"); TYPES.put("CONTRACT_FEE", "合约手续费");
        TYPES.put("OPTION_PNL", "期权盈亏"); TYPES.put("DEPOSIT_FEE", "充值手续费");
        TYPES.put("WITHDRAW_FEE", "提现手续费"); TYPES.put("TRANSFER_IN", "账户转入");
        TYPES.put("TRANSFER_OUT", "账户转出"); TYPES.put("FINANCIAL_YIELD", "理财收益");
        TYPES.put("FINANCIAL_FEE", "理财赎回费"); TYPES.put("LOAN_IN", "贷款放款");
        TYPES.put("LOAN_REPAY", "贷款还款"); TYPES.put("TRIAL_OFFSET", "体验金抵扣");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> query(FundsLedgerQuery q) {
        validate(q);
        long tenant = TenantContext.requireTenantId();
        Long agent = BackendAccess.agentId();
        if (agent == null) agent = q.getFilterAgentId();
        StringBuilder where = new StringBuilder(" where l.amount<>0");
        List<Object> args = new ArrayList<>();
        if (agent != null) condition(where, args, "u.parent_user_id=?", agent);
        if (q.getUserId() != null) condition(where, args, "l.user_id=?", q.getUserId());
        if (present(q.getKeyword())) {
            String pattern = "%" + q.getKeyword().trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where.append(" and (concat('',u.id) like ? escape '!' or lower(coalesce(u.email,'')) like ? escape '!' or lower(coalesce(u.nickname,'')) like ? escape '!' or lower(coalesce(u.phone,'')) like ? escape '!' or lower(coalesce(u.remark,'')) like ? escape '!' or lower(coalesce(l.reference_no,'')) like ? escape '!' or lower(coalesce(l.remark,'')) like ? escape '!' or exists(select 1 from kyc_record k where k.tenant_id=u.tenant_id and k.user_id=u.id and lower(k.real_name) like ? escape '!'))");
            for (int i = 0; i < 8; i++) args.add(pattern);
        }
        if (present(q.getAccountType())) condition(where, args, "l.account_type=?", q.getAccountType());
        if (present(q.getType())) condition(where, args, "l.event_type=?", q.getType());
        if ("IN".equals(q.getDirection())) where.append(" and l.amount>0");
        if ("OUT".equals(q.getDirection())) where.append(" and l.amount<0");
        if (present(q.getCurrency())) condition(where, args, "upper(l.original_currency)=?", q.getCurrency());
        if (q.getStartTime() != null) condition(where, args, "l.event_time>=?", Timestamp.valueOf(q.getStartTime()));
        if (q.getEndTime() != null) condition(where, args, "l.event_time<=?", Timestamp.valueOf(q.getEndTime()));
        if (q.getAmountMin() != null) condition(where, args, "abs(l.amount)>=?", q.getAmountMin());
        if (q.getAmountMax() != null) condition(where, args, "abs(l.amount)<=?", q.getAmountMax());
        String from = " from (" + sources(tenant) + ") l join user_account u on u.tenant_id=" + tenant + " and u.id=l.user_id" + where;
        Map<String, Object> summary = jdbc.queryForObject("select count(*) as total,coalesce(sum(case when l.amount>0 then l.amount else 0 end),0) as income,coalesce(sum(case when l.amount<0 then -l.amount else 0 end),0) as expense" + from,
                (rs, n) -> {
                    Map<String,Object> out = new LinkedHashMap<>();
                    out.put("count", rs.getLong("total")); out.put("income", decimal(rs.getBigDecimal("income")));
                    out.put("expense", decimal(rs.getBigDecimal("expense")));
                    out.put("net", decimal(rs.getBigDecimal("income").subtract(rs.getBigDecimal("expense"))));
                    out.put("currency", "USD"); return out;
                }, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(q.getSize()); pageArgs.add((long) q.getPage() * q.getSize());
        List<Map<String,Object>> rows = jdbc.query("select l.*,u.email,u.nickname,u.phone" + from + " order by l.event_time desc,l.event_key desc limit ? offset ?", this::row, pageArgs.toArray());
        List<Map<String,String>> types = new ArrayList<>();
        TYPES.forEach((value, label) -> { Map<String,String> type = new LinkedHashMap<>(); type.put("value", value); type.put("label", label); types.add(type); });
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("success", true); result.put("list", rows); result.put("total", summary.get("count"));
        result.put("summary", summary); result.put("types", types); result.put("page", q.getPage()); result.put("size", q.getSize());
        return result;
    }

    private Map<String,Object> row(ResultSet rs, int n) throws SQLException {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("key", rs.getString("event_key")); row.put("id", rs.getString("event_key"));
        Timestamp time = rs.getTimestamp("event_time"); row.put("time", time == null ? null : time.toLocalDateTime());
        row.put("userId", rs.getLong("user_id")); row.put("email", rs.getString("email"));
        row.put("nickname", rs.getString("nickname")); row.put("phone", rs.getString("phone"));
        row.put("accountType", rs.getString("account_type")); String type = rs.getString("event_type");
        row.put("type", type); row.put("typeLabel", TYPES.get(type)); row.put("amount", decimal(rs.getBigDecimal("amount")));
        row.put("direction", rs.getBigDecimal("amount").signum() > 0 ? "IN" : "OUT"); row.put("currency", "USD");
        row.put("originalCurrency", rs.getString("original_currency")); row.put("originalAmount", decimal(rs.getBigDecimal("original_amount")));
        row.put("balanceBefore", decimal(rs.getBigDecimal("balance_before"))); row.put("balanceAfter", decimal(rs.getBigDecimal("balance_after")));
        row.put("reference", rs.getString("reference_no")); row.put("remark", rs.getString("remark")); row.put("operator", rs.getString("operator_name"));
        return row;
    }

    static String sources(long tenant) {
        List<String> rows = new ArrayList<>();
        String dep = "deposit_record d left join deposit_credit_record c on c.tenant_id=d.tenant_id and c.deposit_record_id=d.id";
        String depWhere = "d.tenant_id=" + tenant + " and d.status='COMPLETED'";
        String depFee = "case when d.exchange_rate is not null then coalesce(d.fee_amount,0)*d.exchange_rate when d.currency='USD' then coalesce(d.fee_amount,0) else 0 end";
        // amount is net credited USD; show gross and fee separately so their sum equals the receipt.
        rows.add(select("concat('deposit:',d.id)", "d.user_id", "coalesce(c.credited_at,d.credited_at,d.reviewed_at,d.updated_at)", "coalesce(c.account_type,d.account_type,'FUND')", "'DEPOSIT'", "coalesce(c.amount_usd,d.amount)+" + depFee,
                "d.currency", "d.original_amount", "case when coalesce(d.fee_amount,0)=0 then c.balance_before end", "case when coalesce(d.fee_amount,0)=0 then c.balance_after end", "coalesce(d.order_no,concat('D',d.id))", "d.remark", "coalesce(c.operator_name,d.reviewed_by_name,d.created_by_name)", dep, depWhere));
        rows.add(select("concat('deposit-fee:',d.id)", "d.user_id", "coalesce(c.credited_at,d.credited_at,d.reviewed_at,d.updated_at)", "coalesce(c.account_type,d.account_type,'FUND')", "'DEPOSIT_FEE'", "-(" + depFee + ")", "d.currency", "d.fee_amount", "NULL", "NULL", "coalesce(d.order_no,concat('D',d.id))", "d.remark", "coalesce(c.operator_name,d.reviewed_by_name)", dep, depWhere));
        rows.add(simple("withdraw_record", "w", tenant, "withdraw:", "coalesce(w.account_type,'FUND')", "'WITHDRAW'", "-w.amount", "w.updated_at", "w.currency", "w.original_amount", "w.remark", "w.status='COMPLETED'"));
        rows.add(simple("withdraw_record", "w", tenant, "withdraw-fee:", "coalesce(w.account_type,'FUND')", "'WITHDRAW_FEE'", "-coalesce(w.fee,0)", "w.updated_at", "w.currency", "NULL", "w.remark", "w.status='COMPLETED'"));
        for (String account : Arrays.asList("FUND", "CONTRACT", "OPTION")) {
            String start = "locate('" + account + ":',a.changes)";
            String arrow = "locate('->',a.changes," + start + ")";
            String before = "cast(substring(a.changes," + start + "+" + (account.length() + 1) + "," + arrow + "-" + start + "-" + (account.length() + 1) + ") as decimal(32,16))";
            String after = "cast(substring(a.changes," + arrow + "+2,locate(';delta=',a.changes," + arrow + ")+0-" + arrow + "-2) as decimal(32,16))";
            String delta = "(" + after + "-" + before + ")";
            rows.add(select("concat('adjustment:',a.id,':" + account + "')", "a.user_id", "a.created_at", "'" + account + "'", "case when " + delta + ">0 then 'ADMIN_IN' else 'ADMIN_OUT' end", delta, "'USD'", "NULL", before, after, "a.request_key", "a.reason", "concat(coalesce(a.actor_type,'ADMIN'),':',coalesce(concat('',a.actor_id),''))", "balance_adjustment a", "a.tenant_id=" + tenant + " and " + start + ">0 and a.changes<>'CANCELLED'"));
        }
        String contractWhere = "o.status='CLOSED' and (coalesce(o.order_source,'USER')<>'MANUAL_TEST' or o.manual_wallet_enabled=true)";
        String fee = "case when o.lot_size is not null or o.order_source='MANUAL_TEST' then coalesce(o.fee,0) else 0 end";
        String realContract = contractWhere + " and (coalesce(o.funding_source,'')<>'TRIAL' or coalesce(o.profit,0)-(" + fee + ")>0)";
        String contractTime = "case when o.order_source='MANUAL_TEST' then o.created_at else o.close_time end";
        rows.add(simple("contract_order", "o", tenant, "contract-pnl:", "'CONTRACT'", "'CONTRACT_PNL'", "coalesce(o.profit,0)", contractTime, "'USD'", "NULL", "o.symbol", realContract));
        rows.add(simple("contract_order", "o", tenant, "contract-fee:", "'CONTRACT'", "'CONTRACT_FEE'", "-(" + fee + ")", contractTime, "'USD'", "NULL", "o.symbol", realContract));
        rows.add(simple("option_order", "o", tenant, "option-pnl:", "'OPTION'", "'OPTION_PNL'", "coalesce(o.profit,0)", "o.close_time", "'USD'", "NULL", "o.symbol", "o.status='CLOSED' and (coalesce(o.funding_source,'')<>'TRIAL' or o.profit>0)"));
        for (String source : Arrays.asList("CONTRACT", "OPTION")) {
            String table = "CONTRACT".equals(source) ? "contract_order" : "option_order";
            String reason = "CONTRACT".equals(source) ? "CONTRACT_SETTLE:" : "OPTION_SETTLE:";
            rows.add(select("concat('trial-offset-',lower('" + source + "'),':',o.id)", "o.user_id", "o.close_time", "'" + source + "'", "'TRIAL_OFFSET'", "-t.delta", "'USD'", "NULL", "NULL", "NULL", "concat('" + source + "-',o.id)", "o.symbol", "NULL", table + " o join trial_ledger t on t.tenant_id=o.tenant_id and t.user_id=o.user_id and t.reason=concat('" + reason + "',o.id)", "o.tenant_id=" + tenant + " and o.status='CLOSED' and o.funding_source is null and t.delta<0"));
        }
        rows.add(simple("transfer_record", "t", tenant, "transfer-out:", "t.from_account", "'TRANSFER_OUT'", "-t.amount", "t.created_at", "'USD'", "NULL", "concat(t.from_account,' → ',t.to_account)", "1=1"));
        rows.add(simple("transfer_record", "t", tenant, "transfer-in:", "t.to_account", "'TRANSFER_IN'", "t.amount", "t.created_at", "'USD'", "NULL", "concat(t.from_account,' → ',t.to_account)", "1=1"));
        rows.add(simple("financial_yield_record", "y", tenant, "yield:", "'FUND'", "'FINANCIAL_YIELD'", "y.daily_yield", "y.paid_at", "'USD'", "NULL", "y.product_name", "y.status='PAID'"));
        rows.add(simple("financial_order", "f", tenant, "financial-fee:", "'FUND'", "'FINANCIAL_FEE'", "-coalesce(f.penalty_amount,0)", "f.redeem_time", "'USD'", "NULL", "f.product_name", "f.status='REDEEMED'"));
        rows.add(simple("loan_record", "l", tenant, "loan-in:", "'FUND'", "'LOAN_IN'", "l.amount", "l.approved_at", "'USD'", "NULL", "l.remark", "l.approved_at is not null and l.status in ('APPROVED','COMPLETED','OVERDUE')"));
        rows.add(simple("loan_record", "l", tenant, "loan-repay:", "'FUND'", "'LOAN_REPAY'", "-l.repayment_amount", "l.actual_repayment_at", "'USD'", "NULL", "l.remark", "l.status='COMPLETED' and l.actual_repayment_at is not null"));
        return String.join(" union all ", rows);
    }

    private static String simple(String table, String a, long tenant, String prefix, String account, String type, String amount, String time, String currency, String original, String remark, String where) {
        return select("concat('" + prefix + "'," + a + ".id)", a + ".user_id", time, account, type, amount, currency, original, "NULL", "NULL", "concat('" + prefix + "'," + a + ".id)", remark, "NULL", table + " " + a, a + ".tenant_id=" + tenant + " and " + where);
    }
    private static String select(String key, String user, String time, String account, String type, String amount, String currency, String original, String before, String after, String reference, String remark, String operator, String table, String where) {
        return "select " + key + " as event_key," + user + " as user_id," + time + " as event_time," + account + " as account_type," + type + " as event_type,cast(" + amount + " as decimal(32,16)) as amount," + currency + " as original_currency,cast(" + original + " as decimal(32,16)) as original_amount,cast(" + before + " as decimal(32,16)) as balance_before,cast(" + after + " as decimal(32,16)) as balance_after," + reference + " as reference_no," + remark + " as remark," + operator + " as operator_name from " + table + " where " + where;
    }
    private static void condition(StringBuilder sql, List<Object> args, String condition, Object... values) { sql.append(" and ").append(condition); Collections.addAll(args, values); }
    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }
    private static String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private static void validate(FundsLedgerQuery q) {
        if (q.getPage() < 0 || q.getSize() < 1 || q.getSize() > 100) throw new IllegalArgumentException("分页范围无效");
        if (present(q.getKeyword()) && q.getKeyword().length() > 254) throw new IllegalArgumentException("搜索内容不能超过254字符");
        if (present(q.getAccountType()) && !Arrays.asList("FUND", "CONTRACT", "OPTION").contains(q.getAccountType())) throw new IllegalArgumentException("账户类型无效");
        if (present(q.getType()) && !TYPES.containsKey(q.getType())) throw new IllegalArgumentException("资金类型无效");
        if (present(q.getDirection()) && !Arrays.asList("IN", "OUT").contains(q.getDirection())) throw new IllegalArgumentException("收支方向无效");
        if (present(q.getCurrency()) && !q.getCurrency().matches("[A-Z]{3,10}")) throw new IllegalArgumentException("币种无效");
        if (q.getStartTime() != null && q.getEndTime() != null && q.getStartTime().isAfter(q.getEndTime())) throw new IllegalArgumentException("起始时间不能晚于结束时间");
        if (q.getAmountMin() != null && q.getAmountMin().signum() < 0 || q.getAmountMax() != null && q.getAmountMax().signum() < 0 || q.getAmountMin() != null && q.getAmountMax() != null && q.getAmountMin().compareTo(q.getAmountMax()) > 0) throw new IllegalArgumentException("金额范围无效");
    }
}
