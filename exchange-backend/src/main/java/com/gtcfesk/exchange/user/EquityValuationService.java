package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.entity.LoanRecord;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.trade.ContractValuation;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Reads only. Preparation precedes the caller's short REPEATABLE_READ transaction. */
@Service @RequiredArgsConstructor
public class EquityValuationService {
    private final JdbcTemplate jdbc;
    private final ForexQuoteMarketService market;
    @org.springframework.beans.factory.annotation.Value("${market.quote.max-age-ms:60000}") private long maxAgeMs = 60000;
    public static final String BASIS = "net_equity_v1";
    public static final List<String> COMPONENTS = Collections.unmodifiableList(Arrays.asList(
            "wallet_balance", "contract_unrealized_pnl", "option_unrealized_pnl", "receivables",
            "loan_principal", "accrued_interest", "overdue_fees", "accrued_trading_fees", "other_liabilities"));

    public static class Batch {
        public final String id = UUID.randomUUID().toString();
        public final long preparedAt = System.currentTimeMillis();
        public final Map<String, Map<String,Object>> quotes = new LinkedHashMap<>(), rates = new LinkedHashMap<>();
    }
    public static class Value {
        public final Map<String,BigDecimal> amounts = new LinkedHashMap<>();
        public final Set<String> reasons = new LinkedHashSet<>();
        public final Map<String,Object> evidence = new LinkedHashMap<>();
        public final long userId, observedAt;
        public Value(long userId, long observedAt) {
            this.userId=userId; this.observedAt=observedAt;
            COMPONENTS.forEach(k -> amounts.put(k, BigDecimal.ZERO));
        }
        void add(String key, BigDecimal n) {
            if (n == null) { missing(key, "INVALID_" + key.toUpperCase(Locale.ROOT)); return; }
            if (amounts.get(key) != null) amounts.put(key, amounts.get(key).add(n));
        }
        void missing(String key, String reason) { amounts.put(key,null); reasons.add(reason); }
        public void finish() {
            BigDecimal debt = BigDecimal.ZERO;
            for (String k : COMPONENTS.subList(4, COMPONENTS.size())) {
                if (amounts.get(k)==null) { debt=null; break; }
                debt=debt.add(amounts.get(k));
            }
            amounts.put("liabilities_total",debt);
            BigDecimal net=debt == null ? null : debt.negate();
            for (String k : COMPONENTS.subList(0,4)) {
                if (net==null || amounts.get(k)==null) { net=null; break; }
                net=net.add(amounts.get(k));
            }
            amounts.put("net_equity",reasons.isEmpty()?net:null);
            for (Map.Entry<String,BigDecimal> e : amounts.entrySet()) {
                if(e.getValue()==null) continue;
                BigDecimal n=e.getValue().setScale(16,RoundingMode.HALF_UP);
                if(n.precision()-n.scale()>16) {
                    e.setValue(null); reasons.add("AMOUNT_OUT_OF_RANGE");
                } else e.setValue(n);
            }
            if(!reasons.isEmpty()) amounts.put("net_equity",null);
        }
        public String status() { return reasons.isEmpty()?"COMPLETE":"INCOMPLETE"; }
        public Map<String,Object> api() {
            Map<String,Object> result=new LinkedHashMap<>();
            amounts.forEach((k,v)->result.put(k,v==null?null:v.toPlainString()));
            result.put("observedAt",observedAt); result.put("valuationStatus",status()); result.put("reasonCodes",reasons);
            return result;
        }
    }
    static String rateKey(String currency,String source) { return String.valueOf(currency)+"|"+String.valueOf(source); }
    static String placeholders(List<Long> ids) { return String.join(",",Collections.nCopies(ids.size(),"?")); }

    public Batch prepare(List<Long> ids) {
        Batch batch=new Batch();
        if(ids.isEmpty()) return batch;
        jdbc.query("select distinct symbol,quote_currency,quote_source from contract_order where status='OPEN' and user_id in ("+placeholders(ids)+")",
                rs -> {
                    String symbol=rs.getString(1), currency=rs.getString(2), source=rs.getString(3);
                    batch.quotes.computeIfAbsent(symbol,k->snapshot(()->market.snapshotPrice(k)));
                    batch.rates.computeIfAbsent(rateKey(currency,source),k->snapshot(()->market.conversion(currency,source)));
                },ids.toArray());
        return batch;
    }
    static Map<String,Object> snapshot(java.util.function.Supplier<Map<String,Object>> read) {
        try{return new LinkedHashMap<>(read.get());}
        catch(RuntimeException failure){
            Map<String,Object> unavailable=new LinkedHashMap<>();unavailable.put("available",false);
            unavailable.put("preparationError",failure.getClass().getSimpleName());return unavailable;
        }
    }

    public Map<Long,Value> read(JdbcTemplate snapshot, List<Long> ids, Batch batch, long observedAt) {
        Map<Long,Value> values=new LinkedHashMap<>();
        ids.forEach(id->values.put(id,new Value(id,observedAt)));
        if(ids.isEmpty()) return values;
        String users=" user_id in ("+placeholders(ids)+")";
        snapshot.query("select user_id,available,frozen from asset_account where upper(coin) in ('FUND','CONTRACT','OPTION') and"+users,
                rs->{ Value v=values.get(rs.getLong(1)); v.add("wallet_balance",rs.getBigDecimal(2)); v.add("wallet_balance",rs.getBigDecimal(3)); },ids.toArray());
        List<ContractOrder> orders=snapshot.query("select id,user_id,symbol,side,quantity,open_price,lot_size,leverage,fee,quote_currency,quote_source from contract_order where status='OPEN' and"+users,
                new BeanPropertyRowMapper<>(ContractOrder.class),ids.toArray());
        for(ContractOrder order:orders) {
            Value v=values.get(order.getUserId());
            Map<String,Object> input=new LinkedHashMap<>();
            input.put("id",order.getId());input.put("symbol",order.getSymbol());input.put("side",order.getSide());input.put("openPrice",order.getOpenPrice());input.put("quantity",order.getQuantity());input.put("lotSize",order.getLotSize());input.put("leverage",order.getLeverage());input.put("fee",order.getFee());input.put("quoteCurrency",order.getQuoteCurrency());input.put("quoteSource",order.getQuoteSource());
            v.evidence.put("contract_"+order.getId(),input);
            contract(v,order,batch,observedAt,maxAgeMs);
        }
        snapshot.query("select user_id,amount from option_order where status='TRADING' and"+users,
                rs->{ Value v=values.get(rs.getLong(1)); BigDecimal cost=rs.getBigDecimal(2);
                    if(cost==null || cost.signum()<=0)v.missing("option_unrealized_pnl","INVALID_OPTION_COST");
                    else v.evidence.put("costValuedOptionPrincipal",((BigDecimal)v.evidence.getOrDefault("costValuedOptionPrincipal",BigDecimal.ZERO)).add(cost));
                },ids.toArray());
        List<LoanRecord> loans=snapshot.query("select id,user_id,amount,daily_rate,free_days,status,approved_at,actual_repayment_at,repayment_date,overdue_fee,total_interest,repayment_amount from loan_record where"+users,
                new BeanPropertyRowMapper<>(LoanRecord.class),ids.toArray());
        LocalDateTime at=LocalDateTime.ofInstant(Instant.ofEpochMilli(observedAt),ZoneId.systemDefault());
        for(LoanRecord loan:loans) {
            Value v=values.get(loan.getUserId());
            Map<String,Object> input=new LinkedHashMap<>();input.put("status",loan.getStatus());input.put("amount",loan.getAmount());input.put("dailyRate",loan.getDailyRate());input.put("freeDays",loan.getFreeDays());input.put("approvedAt",String.valueOf(loan.getApprovedAt()));input.put("actualRepaymentAt",String.valueOf(loan.getActualRepaymentAt()));input.put("overdueFee",loan.getOverdueFee());input.put("totalInterest",loan.getTotalInterest());input.put("repaymentAmount",loan.getRepaymentAmount());
            v.evidence.put("loan_"+loan.getId(),input);loan(v,loan,at);
        }
        // Generated contractual daily yield has no rejection/approval path; payout only transfers it to cash.
        snapshot.query("select user_id,daily_yield from financial_yield_record where status='PENDING' and"+users,
                rs->{values.get(rs.getLong(1)).add("receivables",rs.getBigDecimal(2));},ids.toArray());
        values.values().forEach(v->{v.evidence.put("quoteBatchId",batch.id);v.evidence.put("method","server_execution_last_price;contract_v1;option_principal_cost_not_fair_value;loan_used_whole_days_v1;recorded_effective_unpaid_overdue_fee;confirmed_pending_yield");v.finish();});
        return values;
    }

    static void contract(Value v,ContractOrder order,Batch batch,long at,long maxAge) {
        if(order.getLotSize()!=null) {
            if(order.getFee()!=null && order.getFee().signum()<0)v.missing("accrued_trading_fees","INVALID_TRADING_FEE");
            else v.add("accrued_trading_fees",order.getFee());
        }
        Map<String,Object> q=batch.quotes.get(order.getSymbol()), r=batch.rates.get(rateKey(order.getQuoteCurrency(),order.getQuoteSource()));
        if(q==null || r==null) {v.missing("contract_unrealized_pnl","QUOTE_SET_CHANGED");return;}
        if(batch.preparedAt>at || at-batch.preparedAt>maxAge || !Boolean.TRUE.equals(q.get("available"))
                || time(q.get("timestamp"))<=0 || time(q.get("timestamp"))>at || time(q.get("expiresAt"))<=at) {
            v.missing("contract_unrealized_pnl","QUOTE_UNAVAILABLE_OR_STALE");return;
        }
        if(!Boolean.TRUE.equals(r.get("conversionAvailable")) || time(r.get("conversionExpiresAt"))<=at
                || time(r.get("conversionTimestamp"))>at || r.get("quoteToUsdRate")==null) {
            v.missing("contract_unrealized_pnl","FX_UNAVAILABLE_OR_STALE");return;
        }
        try {
            BigDecimal price=new BigDecimal(q.get("price").toString()), rate=new BigDecimal(r.get("quoteToUsdRate").toString());
            if(order.getOpenPrice()==null || order.getOpenPrice().signum()<=0 || order.getQuantity()==null || order.getQuantity().signum()<=0
                    || !("BUY".equals(order.getSide()) || "SELL".equals(order.getSide())) || price.signum()<=0 || rate.signum()<=0
                    || order.getLotSize()!=null && order.getLotSize().signum()<=0 || order.getLeverage()!=null && order.getLeverage().signum()<=0) throw new IllegalArgumentException();
            v.add("contract_unrealized_pnl",ContractValuation.quoteProfit(order,price).multiply(rate).setScale(16,RoundingMode.HALF_UP));
        } catch(RuntimeException e) {v.missing("contract_unrealized_pnl","INVALID_CONTRACT_OR_QUOTE");}
    }
    static void loan(Value v,LoanRecord loan,LocalDateTime at) {
        if("COMPLETED".equals(loan.getStatus()) && loan.getActualRepaymentAt()!=null && !loan.getActualRepaymentAt().isAfter(at)) {
            // Existing earlyRepayment pays principal + interest, NOT overdue_fee. A status change alone cannot erase that fee.
            BigDecimal fee=loan.getOverdueFee();
            if(fee==null || fee.signum()==0)return;
            if(fee.signum()<0 || loan.getAmount()==null || loan.getTotalInterest()==null || loan.getRepaymentAmount()==null) {
                v.missing("overdue_fees","OVERDUE_PAYMENT_EVIDENCE_INVALID");return;
            }
            BigDecimal paidFee=loan.getRepaymentAmount().subtract(loan.getAmount()).subtract(loan.getTotalInterest());
            if(paidFee.signum()==0)v.add("overdue_fees",fee);
            else if(paidFee.compareTo(fee)!=0)v.missing("overdue_fees","OVERDUE_PAYMENT_EVIDENCE_INVALID");
            return;
        }
        if(loan.getApprovedAt()==null) {
            if(Arrays.asList("PENDING","SIGNED","REJECTED").contains(loan.getStatus()) && loan.getActualRepaymentAt()==null) return;
            v.missing("loan_principal","LOAN_DISBURSEMENT_EVIDENCE_INVALID");return;
        }
        if(!Arrays.asList("APPROVED","OVERDUE").contains(loan.getStatus()) || loan.getActualRepaymentAt()!=null || loan.getApprovedAt().isAfter(at))
            v.reasons.add("LOAN_STATE_INVALID");
        v.add("loan_principal",loan.getAmount());
        if(loan.getAmount()==null || loan.getAmount().signum()<=0 || loan.getDailyRate()==null || loan.getDailyRate().signum()<0 || loan.getFreeDays()==null || loan.getFreeDays()<0) {
            v.missing("accrued_interest","LOAN_TERMS_INVALID");return;
        }
        v.add("accrued_interest",LoanInterest.accrued(loan.getAmount(),loan.getDailyRate(),loan.getFreeDays(),Math.max(0,ChronoUnit.DAYS.between(loan.getApprovedAt(),at))));
        // Approved business basis: the recorded effective unpaid amount only; never accrue a new penalty.
        // This schema has no partial-payment path; completed principal/interest and fee evidence are handled above.
        if(loan.getOverdueFee()!=null && loan.getOverdueFee().signum()<0) v.missing("overdue_fees","INVALID_RECORDED_OVERDUE_FEE");
        else v.add("overdue_fees",loan.getOverdueFee()==null?BigDecimal.ZERO:loan.getOverdueFee());
    }
    static long time(Object o) { return o instanceof Number?((Number)o).longValue():0; }
}
