package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.trade.ContractValuation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssetEquityValuationTest {
    static BigDecimal n(String s){return new BigDecimal(s);}
    static void equal(String expected,BigDecimal actual){assertNotNull(actual);assertEquals(0,n(expected).compareTo(actual));}
    static EquityValuationService.Value value(String wallet){EquityValuationService.Value v=new EquityValuationService.Value(1,1);v.add("wallet_balance",n(wallet));return v;}
    static ContractOrder order(String side,boolean legacy){
        ContractOrder o=new ContractOrder();o.setSymbol("TEST");o.setSide(side);o.setOpenPrice(n("100"));o.setQuantity(n("2"));o.setLotSize(legacy?null:n("10"));o.setLeverage(n("5"));o.setFee(n("3"));o.setQuoteCurrency("EUR");o.setQuoteSource("yahoo");return o;
    }
    static EquityValuationService.Batch batch(String price,String rate){
        EquityValuationService.Batch b=new EquityValuationService.Batch();Map<String,Object> q=new HashMap<>(),r=new HashMap<>();
        q.put("price",n(price));q.put("timestamp",b.preparedAt);q.put("expiresAt",b.preparedAt+15000);q.put("available",true);
        r.put("quoteToUsdRate",n(rate));r.put("conversionAvailable",true);r.put("conversionExpiresAt",b.preparedAt+15000);r.put("conversionTimestamp",b.preparedAt);
        b.quotes.put("TEST",q);b.rates.put("EUR|yahoo",r);return b;
    }
    static LoanRecord loan(LocalDateTime now){
        LoanRecord l=new LoanRecord();l.setStatus("APPROVED");l.setAmount(n("500"));l.setDailyRate(n("1"));l.setFreeDays(1);l.setApprovedAt(now.minusDays(3));l.setRepaymentDate(now.plusDays(7));return l;
    }
    @Test void cashAndSignedNegativeBalances(){for(String wallet:Arrays.asList("1000","0","-80")){EquityValuationService.Value v=value(wallet);v.finish();equal(wallet,v.amounts.get("net_equity"));assertEquals("COMPLETE",v.status());}}
    @Test void positiveNegativeLongShortLegacyAndFxUseSettlementArithmetic(){
        for(String side:Arrays.asList("BUY","SELL"))for(boolean legacy:Arrays.asList(false,true))for(String price:Arrays.asList("105","96")) {
            ContractOrder o=order(side,legacy);EquityValuationService.Batch b=batch(price,"1.2");EquityValuationService.Value v=value("1000");
            EquityValuationService.contract(v,o,b,b.preparedAt,15000);v.finish();
            BigDecimal profit=ContractValuation.quoteProfit(o,n(price)).multiply(n("1.2"));
            equal(profit.toPlainString(),v.amounts.get("contract_unrealized_pnl"));
            equal(n("1000").add(profit).subtract(legacy?BigDecimal.ZERO:n("3")).toPlainString(),v.amounts.get("net_equity"));
        }
    }
    @Test void marginFeeAndProfitTransitionNotDoubleCounted(){
        EquityValuationService.Batch b=batch("105","1");EquityValuationService.Value before=value("1000");
        EquityValuationService.contract(before,order("BUY",false),b,b.preparedAt,15000);before.finish();equal("1097",before.amounts.get("net_equity"));
        EquityValuationService.Value after=value("1097");after.finish();equal("1097",after.amounts.get("net_equity"));
        // Frozen margin/principal/withdrawal reserves are already part of wallet; no independent addition.
    }
    @Test void loanCashPrincipalInterestAndRepaymentAreContinuous(){
        LocalDateTime now=LocalDateTime.now();LoanRecord l=loan(now);
        EquityValuationService.Value v=value("1500");EquityValuationService.loan(v,l,now);v.finish();equal("990",v.amounts.get("net_equity"));
        l.setStatus("COMPLETED");l.setActualRepaymentAt(now);v=value("990");EquityValuationService.loan(v,l,now);v.finish();equal("990",v.amounts.get("net_equity"));
    }
    @Test void undisbursedLoansHaveNoDebtAndAnomalousStatesDoNotDisappear(){
        LocalDateTime now=LocalDateTime.now();
        for(String status:Arrays.asList("PENDING","SIGNED","REJECTED")){LoanRecord l=loan(now);l.setApprovedAt(null);l.setStatus(status);EquityValuationService.Value v=value("1000");EquityValuationService.loan(v,l,now);v.finish();equal("1000",v.amounts.get("net_equity"));}
        LoanRecord l=loan(now);l.setStatus("REJECTED");EquityValuationService.Value v=value("1000");EquityValuationService.loan(v,l,now);v.finish();assertNull(v.amounts.get("net_equity"));equal("500",v.amounts.get("loan_principal"));
    }
    @Test void onlyRecordedOverdueFeeAndCompletedDebtIsReleased(){
        LocalDateTime now=LocalDateTime.now();LoanRecord l=loan(now);l.setStatus("OVERDUE");l.setRepaymentDate(now.minusDays(1));l.setOverdueFee(n("12"));
        EquityValuationService.Value v=value("1500");EquityValuationService.loan(v,l,now);v.finish();equal("978",v.amounts.get("net_equity"));
        l.setOverdueFee(null);v=value("1500");EquityValuationService.loan(v,l,now);v.finish();equal("990",v.amounts.get("net_equity"));
        l.setStatus("COMPLETED");l.setActualRepaymentAt(now);l.setOverdueFee(n("12"));l.setTotalInterest(n("10"));l.setRepaymentAmount(n("522"));v=value("978");EquityValuationService.loan(v,l,now);v.finish();equal("978",v.amounts.get("net_equity"));
        l.setRepaymentAmount(n("510"));v=value("990");EquityValuationService.loan(v,l,now);v.finish();equal("978",v.amounts.get("net_equity"));equal("12",v.amounts.get("overdue_fees"));
        l.setRepaymentAmount(n("511"));v=value("989");EquityValuationService.loan(v,l,now);v.finish();assertNull(v.amounts.get("net_equity"));
    }
    @Test void staleMissingFutureAndInvalidQuotesAreNeverZeroPnl(){
        for(int variant=0;variant<7;variant++){
            EquityValuationService.Batch b=batch("105","1.2");long at=b.preparedAt;
            if(variant==0)b.quotes.clear();if(variant==1)b.quotes.get("TEST").put("available",false);
            if(variant==2)b.quotes.get("TEST").put("expiresAt",at);if(variant==3)b.quotes.get("TEST").put("timestamp",at+1);
            if(variant==4)b.rates.get("EUR|yahoo").put("conversionAvailable",false);if(variant==5)b.rates.get("EUR|yahoo").put("quoteToUsdRate",null);if(variant==6)at+=15001;
            EquityValuationService.Value v=value("1000");EquityValuationService.contract(v,order("BUY",false),b,at,15000);v.finish();assertNull(v.amounts.get("net_equity"));assertNull(v.amounts.get("contract_unrealized_pnl"));assertEquals("INCOMPLETE",v.status());
        }
    }
    @Test void interestUsesWholeDaysAndFreeDaysWithoutFullTermInterest(){equal("0",LoanInterest.accrued(n("500"),n("1"),2,1));equal("10",LoanInterest.accrued(n("500"),n("1"),1,3));}
    @Test void providerExceptionBecomesAnUnavailableSnapshotRatherThanLosingTheMinute(){
        EquityValuationService.Batch b=batch("105","1.2");
        b.quotes.put("TEST",EquityValuationService.snapshot(()->{throw new IllegalStateException("fixture outage");}));
        EquityValuationService.Value v=value("1000");EquityValuationService.contract(v,order("BUY",false),b,b.preparedAt,15000);v.finish();
        equal("1000",v.amounts.get("wallet_balance"));assertNull(v.amounts.get("net_equity"));assertEquals("INCOMPLETE",v.status());
    }
    @Test void unknownComponentAndOverflowFailClosed(){EquityValuationService.Value v=value("1000");v.missing("receivables","UNKNOWN");v.finish();assertNull(v.amounts.get("net_equity"));v=value("10000000000000000");v.finish();assertEquals("INCOMPLETE",v.status());assertNull(v.amounts.get("net_equity"));}
}
