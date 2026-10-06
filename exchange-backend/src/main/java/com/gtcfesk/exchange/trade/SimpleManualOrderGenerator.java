package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.*;
import java.util.*;

/** Simulation-only: newest feasible OHLC minute wins, never changes historical candles. */
public final class SimpleManualOrderGenerator {
    private SimpleManualOrderGenerator() { }
    public static final int NO_SOLUTION=422;
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
    /** Validate fixed inputs before any history read; impossible fixed-price signs cannot be repaired by more lots. */
    public static void validate(Request r,TradingSymbol s,BigDecimal maxLeverage) {
        tolerance(r.netTolerance);
        if(r.side!=null && !Arrays.asList("BUY","SELL").contains(r.side))throw new BusinessException("方向无效");
        if(r.openPrice!=null)ManualOrderCalculation.positive(r.openPrice,"开仓价格");
        if(r.closePrice!=null)ManualOrderCalculation.positive(r.closePrice,"平仓价格");
        if(r.targetNet!=null)ManualOrderCalculation.number(r.targetNet,32,16,"目标净收益");
        if(r.quantity!=null)QuantityRules.quantity(s,r.quantity);
        BigDecimal leverage=r.leverage==null?HUNDRED.min(maxLeverage):r.leverage;
        ManualOrderCalculation.leverage(leverage,maxLeverage);
        if(r.openPrice!=null && r.closePrice!=null && r.targetNet!=null && r.targetNet.signum()>0
                && (r.openPrice.compareTo(r.closePrice)==0 || "BUY".equals(r.side) && r.closePrice.compareTo(r.openPrice)<=0 || "SELL".equals(r.side) && r.closePrice.compareTo(r.openPrice)>=0))
            throw new BusinessException("固定开平仓价格和方向无法产生正净收益；请调整价格、重新选择图表时间或清空固定条件，增加手数不能解决；未创建订单或修改资金");
    }
    private static BigDecimal net(BigDecimal gross,BigDecimal fee,BigDecimal q) {
        return ManualOrderCalculation.money(ManualOrderCalculation.money(gross.multiply(q)).subtract(ManualOrderCalculation.money(fee.multiply(q))));
    }
    /** Fixed prices determine per-lot profit at a close; reject impossible lot rounding before searching opening times. */
    private static boolean fixedTargetPossible(Request r,BigDecimal pc,BigDecimal lot,BigDecimal rate,BigDecimal fee,BigDecimal step,BigDecimal minimum) {
        if(r.openPrice==null || r.targetNet==null)return true;
        for(String side:r.side==null?Arrays.asList("BUY","SELL"):Collections.singletonList(r.side)) {
            BigDecimal gross=pc.subtract(r.openPrice).multiply(lot).multiply(rate).multiply("BUY".equals(side)?ONE:ONE.negate()),u=gross.subtract(fee);
            if(r.targetNet.signum()!=0 && u.signum()!=r.targetNet.signum())continue;
            // Sub-ledger steps may have rounding plateaus; retain the authoritative candidate checks there.
            if(r.quantity==null && u.signum()!=0 && u.abs().multiply(step).compareTo(ONE.scaleByPowerOfTen(-16))<=0)return true;
            Set<BigDecimal> qs=new LinkedHashSet<>();
            if(r.quantity!=null)qs.add(r.quantity);
            else {
                quantities(qs,minimum,step);
                if(u.signum()!=0) {
                    quantities(qs,r.targetNet.divide(u,48,RoundingMode.HALF_UP),step);
                    if(r.allowNetAdjustment)for(BigDecimal factor:Arrays.asList(ONE.subtract(r.netTolerance.divide(HUNDRED)),ONE.add(r.netTolerance.divide(HUNDRED))))
                        quantities(qs,r.targetNet.multiply(factor).divide(u,48,RoundingMode.HALF_UP),step);
                }
            }
            for(BigDecimal q:qs)try{if(q.compareTo(minimum)>=0 && matches(net(gross,fee,q),r.targetNet,r.allowNetAdjustment,r.netTolerance))return true;}catch(BusinessException invalid){ }
        }
        return false;
    }
    /** OHLC interval index: find only overlapping opening minutes, newest first, skipping disjoint pairs. */
    private static final class OpeningIndex {
        final List<ManualOrderGenerator.Candle> rows;final long[] times;final BigDecimal[] low,high;
        OpeningIndex(NavigableMap<Long,ManualOrderGenerator.Candle> candles){rows=new ArrayList<>(candles.values());times=new long[rows.size()];low=new BigDecimal[rows.size()*4];high=new BigDecimal[rows.size()*4];for(int i=0;i<times.length;i++)times[i]=rows.get(i).time;build(1,0,rows.size()-1);}
        void build(int node,int a,int b){if(a==b){low[node]=rows.get(a).low;high[node]=rows.get(a).high;return;}int middle=(a+b)/2;build(node*2,a,middle);build(node*2+1,middle+1,b);low[node]=low[node*2].min(low[node*2+1]);high[node]=high[node*2].max(high[node*2+1]);}
        int last(int before,List<BigDecimal[]> bands){return find(1,0,rows.size()-1,before,bands);}
        int find(int node,int a,int b,int before,List<BigDecimal[]> bands){
            if(a>before)return -1;boolean overlaps=false;for(BigDecimal[] band:bands)if(low[node].compareTo(band[1])<=0 && high[node].compareTo(band[0])>=0){overlaps=true;break;}if(!overlaps)return -1;
            if(a==b)return a;int middle=(a+b)/2,right=find(node*2+1,middle+1,b,before,bands);return right>=0?right:find(node*2,a,middle,before,bands);
        }
    }
    private static void searchBudget(long deadline) {
        if(System.nanoTime()-deadline>=0)throw new BusinessException("匹配范围过大，已停止长时间搜索；请固定图表开平仓分钟或调整净收益条件后重试；未创建订单或修改资金");
    }
    public static Candidate solve(Request r,NavigableMap<Long,ManualOrderGenerator.Candle> candles,TradingSymbol s,BigDecimal available,BigDecimal maxLeverage) {
        validate(r,s,maxLeverage);
        if(!candles.isEmpty())validateTimes(r,candles.firstKey(),candles.lastKey()+60000);
        BigDecimal leverage=r.leverage==null?HUNDRED.min(maxLeverage):r.leverage;
        if(candles.isEmpty())throw new BusinessException(NO_SOLUTION,"最近七天没有有效的已结束分钟OHLC行情及换算率");
        BigDecimal lot=s.getLotSize()==null?number("1000"):s.getLotSize(),fee=s.getFeeMultiplier()==null?number("30"):s.getFeeMultiplier();
        BigDecimal step=s.getQuantityStep()==null?number("0.01"):s.getQuantityStep(),minimum=s.getMinOrderQuantity()==null?step:s.getMinOrderQuantity();
        Candidate nonPositive=null;
        // ponytail: fully overlapping OHLC can still visit O(n²) pairs. Bound CPU time; use a profit/notional index if broad searches must always finish.
        long deadline=System.nanoTime()+1_000_000_000L;
        OpeningIndex openings=new OpeningIndex(candles);
        Collection<ManualOrderGenerator.Candle> closes=r.closeTime!=null ? candles.containsKey(r.closeTime)?Collections.singletonList(candles.get(r.closeTime)):Collections.emptyList()
            :r.closePrice==null?Collections.singletonList(candles.lastEntry().getValue()):candles.descendingMap().values();
        for(ManualOrderGenerator.Candle close:closes) {
            searchBudget(deadline);
            BigDecimal pc=r.closePrice==null?close.price:r.closePrice;
            if(pc.compareTo(close.low)<0 || pc.compareTo(close.high)>0)continue;
            if(!fixedTargetPossible(r,pc,lot,close.rate,fee,step,minimum))continue;
            List<BigDecimal[]> bands=r.openPrice!=null?Collections.singletonList(new BigDecimal[]{r.openPrice,r.openPrice}):Arrays.asList(new BigDecimal[]{pc.multiply(number("0.992")),pc.multiply(number("0.997"))},new BigDecimal[]{pc.multiply(number("1.003")),pc.multiply(number("1.008"))});
            if(r.openPrice==null && r.quantity==null && r.targetNet!=null) {
                List<BigDecimal[]> feasible=new ArrayList<>();BigDecimal multiplier=lot.multiply(close.rate),rounding=ONE.scaleByPowerOfTen(-16);
                BigDecimal allowance=r.allowNetAdjustment?r.targetNet.abs().multiply(r.netTolerance).divide(HUNDRED):ZERO;
                for(String side:r.side==null?Arrays.asList("BUY","SELL"):Collections.singletonList(r.side))for(BigDecimal[] band:bands) {
                    int sign="BUY".equals(side)?1:-1;
                    if(r.targetNet.signum()==0) {
                        // Any positive legal lot count is at least minimum; ledger rounding cannot hide a larger unit loss.
                        BigDecimal a=opening(rounding,minimum,pc,multiplier,fee,sign),b=opening(rounding.negate(),minimum,pc,multiplier,fee,sign);
                        BigDecimal low=band[0].max(a.min(b)),high=band[1].min(a.max(b));if(low.compareTo(high)<=0)feasible.add(new BigDecimal[]{low,high});
                    } else {
                        BigDecimal a=unit(band[0],pc,multiplier,fee,sign).multiply(BigDecimal.valueOf(r.targetNet.signum())),b=unit(band[1],pc,multiplier,fee,sign).multiply(BigDecimal.valueOf(r.targetNet.signum()));
                        BigDecimal largest=a.max(b),smallest=a.min(b);
                        if(largest.signum()>0 && largest.multiply(number("9999999999999999.9999999999999999")).add(rounding).compareTo(r.targetNet.abs().subtract(allowance))>=0
                            && (smallest.signum()<=0 || smallest.multiply(minimum).subtract(rounding).compareTo(r.targetNet.abs().add(allowance))<=0))feasible.add(band);
                    }
                }
                bands=feasible;
            }
            if(r.openPrice==null && r.quantity!=null && r.targetNet!=null) {
                List<BigDecimal[]> feasible=new ArrayList<>();BigDecimal allowance=r.allowNetAdjustment?r.targetNet.abs().multiply(r.netTolerance).divide(HUNDRED):ZERO;
                BigDecimal rounding=ONE.scaleByPowerOfTen(-16),multiplier=lot.multiply(close.rate);
                for(String side:r.side==null?Arrays.asList("BUY","SELL"):Collections.singletonList(r.side)) {
                    int sign="BUY".equals(side)?1:-1;
                    BigDecimal a=opening(r.targetNet.subtract(allowance).subtract(rounding),r.quantity,pc,multiplier,fee,sign),b=opening(r.targetNet.add(allowance).add(rounding),r.quantity,pc,multiplier,fee,sign);
                    for(BigDecimal[] band:bands){BigDecimal low=band[0].max(a.min(b)),high=band[1].min(a.max(b));if(low.compareTo(high)<=0)feasible.add(new BigDecimal[]{low,high});}
                }
                bands=feasible;
            }
            int before=Arrays.binarySearch(openings.times,close.time)-1;
            for(int index=r.openTime==null?openings.last(before,bands):Arrays.binarySearch(openings.times,r.openTime);index>=0 && index<=before;index=r.openTime==null?openings.last(index-1,bands):-1) {
                searchBudget(deadline);
                ManualOrderGenerator.Candle open=openings.rows.get(index);
                List<BigDecimal[]> ranges=new ArrayList<>();
                if(r.openPrice!=null) {
                    if(r.openPrice.compareTo(open.low)<0 || r.openPrice.compareTo(open.high)>0)continue;
                    ranges.add(new BigDecimal[]{r.openPrice,r.openPrice});
                } else for(BigDecimal[] band:bands) {
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
        throw new BusinessException(NO_SOLUTION,"最近七天已就绪行情没有符合全部固定条件的开平仓组合；自动价差须为0.3%至0.8%，开仓须早于平仓，"+(r.allowNetAdjustment?"净收益须在设置容差内":"严格净收益须精确相等")+"；未创建订单或修改资金");
    }
}
