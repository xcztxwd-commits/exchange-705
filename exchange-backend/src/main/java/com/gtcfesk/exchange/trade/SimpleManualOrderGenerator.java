package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.*;
import java.util.*;

/** Simulation-only: newest feasible OHLC minute wins, never changes historical candles. */
public final class SimpleManualOrderGenerator {
    private SimpleManualOrderGenerator() { }
    private static final BigDecimal ZERO=BigDecimal.ZERO, ONE=BigDecimal.ONE, HUNDRED=new BigDecimal("100");
    public static class Request {
        public Long userId,specVersion,openTime,closeTime;
        public String symbol,timezone,side,quantityUnitType;
        public BigDecimal openPrice,closePrice,leverage,quantity,targetNet;
        public BigDecimal netTolerance=new BigDecimal("5");
        public boolean allowNetAdjustment=true,walletEnabled,historyEnabled;
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String key,Object value) {throw new BusinessException("不支持的简版生成参数: "+key);}
    }
    public static final class Candidate {
        public ManualOrderGenerator.Candle open,close;
        public BigDecimal openPrice,closePrice,leverage;
        public String side;
        public Map<String,BigDecimal> calculation;
    }
    public static void tolerance(BigDecimal value) {
        ManualOrderCalculation.number(value,5,2,"净收益容差");
        if(value.signum()<0 || value.compareTo(HUNDRED)>=0)throw new BusinessException("净收益容差必须在 0 至 100% 之间，不含100%");
    }
    public static void validateTimes(Request r,long from,long end) {
        for(Long time:Arrays.asList(r.openTime,r.closeTime))if(time!=null && (time<from || time>=end || time%60000!=0))
            throw new BusinessException("图表时间必须是最近七天内已结束的整分钟");
        if(r.openTime!=null && r.closeTime!=null && r.openTime>=r.closeTime)throw new BusinessException("图表开仓时间必须早于平仓时间");
    }
    public static boolean matches(BigDecimal actual,BigDecimal target,boolean adjust,BigDecimal tolerance) {
        if(target==null)return true;
        if(!adjust || target.signum()==0)return actual.compareTo(target)==0;
        return actual.subtract(target).abs().multiply(HUNDRED).compareTo(target.abs().multiply(tolerance))<=0;
    }
    private static BigDecimal number(String value) {return new BigDecimal(value);}
    private static void quantities(Set<BigDecimal> out,BigDecimal value,BigDecimal step) {
        if(value==null || value.signum()<=0)return;
        for(RoundingMode rounding:Arrays.asList(RoundingMode.FLOOR,RoundingMode.CEILING)) {
            BigDecimal q=value.divide(step,0,rounding).multiply(step);
            if(q.signum()>0 && q.precision()-q.scale()<=16)out.add(q.stripTrailingZeros());
        }
    }
    private static void prices(Set<BigDecimal> out,BigDecimal value,BigDecimal low,BigDecimal high) {
        if(value==null)return;
        value=value.max(low).min(high);
        for(RoundingMode rounding:Arrays.asList(RoundingMode.HALF_UP,RoundingMode.FLOOR,RoundingMode.CEILING)) {
            BigDecimal p=value.setScale(16,rounding).stripTrailingZeros();
            if(p.compareTo(low)>=0 && p.compareTo(high)<=0 && p.signum()>0)out.add(p);
        }
    }
    private static BigDecimal unit(BigDecimal p,BigDecimal close,BigDecimal multiplier,BigDecimal fee,int sign) {
        return close.subtract(p).multiply(multiplier).multiply(BigDecimal.valueOf(sign)).subtract(fee);
    }
    private static BigDecimal opening(BigDecimal net,BigDecimal q,BigDecimal close,BigDecimal multiplier,BigDecimal fee,int sign) {
        return close.subtract(net.divide(q,48,RoundingMode.HALF_UP).add(fee).divide(multiplier,48,RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(sign)));
    }
    public static BigDecimal marginRate(TradingSymbol s,ManualOrderGenerator.Candle c,BigDecimal p) {
        if(!FxContractRules.isForex(s))return p.multiply(c.rate);
        if("USD".equals(s.getBaseCurrency()))return ONE;
        return "USD".equals(s.getQuoteCurrency())?p:c.marginRate;
    }
    public static Candidate solve(Request r,NavigableMap<Long,ManualOrderGenerator.Candle> candles,TradingSymbol s,BigDecimal available,BigDecimal maxLeverage) {
        tolerance(r.netTolerance);
        if(!candles.isEmpty())validateTimes(r,candles.firstKey(),candles.lastKey()+60000);
        if(r.side!=null && !Arrays.asList("BUY","SELL").contains(r.side))throw new BusinessException("方向无效");
        if(r.openPrice!=null)ManualOrderCalculation.positive(r.openPrice,"开仓价格");
        if(r.closePrice!=null)ManualOrderCalculation.positive(r.closePrice,"平仓价格");
        if(r.targetNet!=null)ManualOrderCalculation.number(r.targetNet,32,16,"目标净收益");
        if(r.quantity!=null)QuantityRules.quantity(s,r.quantity);
        BigDecimal leverage=r.leverage==null?HUNDRED.min(maxLeverage):r.leverage;
        ManualOrderCalculation.leverage(leverage,maxLeverage);
        if(candles.isEmpty())throw new BusinessException("最近七天没有有效的已结束分钟OHLC行情及换算率");
        BigDecimal lot=s.getLotSize()==null?number("1000"):s.getLotSize(),fee=s.getFeeMultiplier()==null?number("30"):s.getFeeMultiplier();
        BigDecimal step=s.getQuantityStep()==null?number("0.01"):s.getQuantityStep(),minimum=s.getMinOrderQuantity()==null?step:s.getMinOrderQuantity();
        Candidate nonPositive=null;
        // ponytail: at most seven days of minute pairs; add an interval index if sparse worst-case searches become measurable.
        for(ManualOrderGenerator.Candle close:candles.descendingMap().values()) {
            if(r.closeTime!=null && close.time!=r.closeTime)continue;
            if(r.closeTime==null && r.closePrice==null && close!=candles.lastEntry().getValue())break;
            BigDecimal pc=r.closePrice==null?close.price:r.closePrice;
            if(pc.compareTo(close.low)<0 || pc.compareTo(close.high)>0)continue;
            // Fixed prices and quantity determine profit at this close rate, independent of opening time.
            // Reject incompatible targets before scanning minute pairs (common fully-fixed failure is O(n)).
            if(r.openPrice!=null && r.quantity!=null && r.targetNet!=null) {
                BigDecimal gross=ManualOrderCalculation.money(pc.subtract(r.openPrice).multiply(lot).multiply(close.rate).multiply(r.quantity));
                BigDecimal charge=ManualOrderCalculation.money(fee.multiply(r.quantity));
                boolean buy=(r.side==null || "BUY".equals(r.side)) && matches(gross.subtract(charge),r.targetNet,r.allowNetAdjustment,r.netTolerance);
                boolean sell=(r.side==null || "SELL".equals(r.side)) && matches(gross.negate().subtract(charge),r.targetNet,r.allowNetAdjustment,r.netTolerance);
                if(!buy && !sell)continue;
            }
            for(ManualOrderGenerator.Candle open:candles.headMap(close.time,false).descendingMap().values()) {
                if(r.openTime!=null && open.time!=r.openTime)continue;
                List<BigDecimal[]> ranges=new ArrayList<>();
                if(r.openPrice!=null) {
                    if(r.openPrice.compareTo(open.low)<0 || r.openPrice.compareTo(open.high)>0)continue;
                    ranges.add(new BigDecimal[]{r.openPrice,r.openPrice});
                } else for(BigDecimal[] band:Arrays.asList(new BigDecimal[]{pc.multiply(number("0.992")),pc.multiply(number("0.997"))},new BigDecimal[]{pc.multiply(number("1.003")),pc.multiply(number("1.008"))})) {
                    BigDecimal low=band[0].max(open.low),high=band[1].min(open.high);
                    if(low.compareTo(high)<=0)ranges.add(new BigDecimal[]{low,high});
                }
                Candidate best=null;
                for(String side:r.side==null?Arrays.asList("BUY","SELL"):Collections.singletonList(r.side))for(BigDecimal[] range:ranges) {
                    int sign="BUY".equals(side)?1:-1;
                    BigDecimal low=range[0],high=range[1],multiplier=lot.multiply(close.rate);
                    BigDecimal anchor=pc.multiply(sign==1?number("0.995"):number("1.005")).max(low).min(high);
                    Set<BigDecimal> qs=new LinkedHashSet<>();
                    if(r.quantity!=null)qs.add(r.quantity);
                    else if(r.targetNet==null || r.targetNet.signum()==0)quantities(qs,ONE.max(minimum),step);
                    else {
                        for(BigDecimal price:Arrays.asList(anchor,low,high,low.add(high).divide(number("2")))) {
                            BigDecimal u=unit(price,pc,multiplier,fee,sign);
                            if(u.signum()==r.targetNet.signum())quantities(qs,r.targetNet.divide(u,48,RoundingMode.HALF_UP),step);
                        }
                        int netSign=r.targetNet.signum();
                        BigDecimal a=unit(low,pc,multiplier,fee,sign).multiply(BigDecimal.valueOf(netSign)),b=unit(high,pc,multiplier,fee,sign).multiply(BigDecimal.valueOf(netSign));
                        BigDecimal max=a.max(b),min=a.min(b),tol=r.allowNetAdjustment?r.netTolerance.divide(HUNDRED):ZERO;
                        if(max.signum()>0)quantities(qs,r.targetNet.abs().multiply(ONE.subtract(tol)).divide(max,48,RoundingMode.CEILING),step);
                        if(min.signum()>0)quantities(qs,r.targetNet.abs().multiply(ONE.add(tol)).divide(min,48,RoundingMode.FLOOR),step);
                        quantities(qs,minimum,step);
                        if(!r.allowNetAdjustment) {
                            // Decimal-friendly quantities avoid needlessly missing exact solutions after price rounding.
                            for(int exponent=-2;exponent<=15;exponent++)for(int mantissa:new int[]{1,2,5})
                                quantities(qs,BigDecimal.valueOf(mantissa).scaleByPowerOfTen(exponent),step);
                        }
                    }
                    if(r.quantity==null && s.getMinOrderNotional()!=null && s.getMinOrderNotional().signum()>0)
                        quantities(qs,s.getMinOrderNotional().divide(lot.multiply(anchor).multiply(open.rate),48,RoundingMode.CEILING),step);
                    for(BigDecimal q:qs) {
                        Set<BigDecimal> ps=new LinkedHashSet<>();
                        if(r.openPrice!=null)ps.add(r.openPrice);
                        else {
                            if(r.targetNet!=null) {
                                prices(ps,opening(r.targetNet,q,pc,multiplier,fee,sign),low,high);
                                if(r.allowNetAdjustment)for(BigDecimal factor:Arrays.asList(ONE.subtract(r.netTolerance.divide(HUNDRED)),ONE.add(r.netTolerance.divide(HUNDRED))))
                                    prices(ps,opening(r.targetNet.multiply(factor),q,pc,multiplier,fee,sign),low,high);
                            }
                            prices(ps,anchor,low,high);prices(ps,low,low,high);prices(ps,high,low,high);
                        }
                        for(BigDecimal po:ps)try {
                            QuantityRules.quantity(s,q);QuantityRules.notional(q,lot,po,open.rate,s.getMinOrderNotional());
                            Map<String,BigDecimal> calc=ManualOrderCalculation.calculate("QUANTITY",q,side,available,po,pc,lot,leverage,open.rate,close.rate,fee,marginRate(s,open,po),step);
                            if(!matches(calc.get("net"),r.targetNet,r.allowNetAdjustment,r.netTolerance))continue;
                            Candidate c=new Candidate();c.open=open;c.close=close;c.openPrice=po;c.closePrice=pc;c.leverage=leverage;c.side=side;c.calculation=calc;
                            if(best==null || r.targetNet==null && c.calculation.get("net").signum()>best.calculation.get("net").signum()
                                || r.targetNet!=null && c.calculation.get("net").subtract(r.targetNet).abs().compareTo(best.calculation.get("net").subtract(r.targetNet).abs())<0)best=c;
                        }catch(BusinessException | ArithmeticException invalid) { /* An invalid arithmetic candidate never relaxes a fixed input. */ }
                    }
                }
                if(best!=null) {
                    if(r.targetNet!=null || best.calculation.get("net").signum()>0)return best;
                    if(nonPositive==null)nonPositive=best;
                }
            }
        }
        if(nonPositive!=null)return nonPositive;
        throw new BusinessException("最近七天没有符合全部固定条件的开平仓组合；自动价差须为0.3%至0.8%，开仓须早于平仓，"+(r.allowNetAdjustment?"净收益须在设置容差内":"严格净收益须精确相等")+"；未创建订单或修改资金");
    }
}
