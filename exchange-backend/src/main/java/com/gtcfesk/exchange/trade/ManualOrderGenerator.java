package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import java.math.*;
import java.util.*;

/** Bounded, side-effect-free search over actual historical minute opens. */
public final class ManualOrderGenerator {
    private ManualOrderGenerator() { }
    public static final long MINUTE=60000L, RANGE=7*24*60*MINUTE;
    private static final BigDecimal HUNDRED=new BigDecimal("100"), STEP=new BigDecimal("0.01"), LOWER=new BigDecimal("0.95"), UPPER=new BigDecimal("1.05");
    public static class Request {
        public Long userId;
        public Long specVersion;
        public String quantityUnitType;
        public String symbol,timezone,openLocal,closeLocal,openOffset,closeOffset,side;
        public BigDecimal leverage,quantity,percent,targetNet,targetClosePrice;
        public boolean walletEnabled,historyEnabled;
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String key,Object value) {throw new BusinessException("不支持的生成参数: "+key);}
    }
    public static final class Candle {
        public final long time;
        public final BigDecimal price,rate,marginRate,low,high,closePrice;
        public Candle(long time,BigDecimal price,BigDecimal rate) {this(time,price,rate,price.multiply(rate));}
        public Candle(long time,BigDecimal price,BigDecimal rate,BigDecimal marginRate) {this(time,price,rate,marginRate,null,null,null);}
        public Candle(long time,BigDecimal price,BigDecimal rate,BigDecimal marginRate,BigDecimal low,BigDecimal high,BigDecimal closePrice) {this.time=time;this.price=price;this.rate=rate;this.marginRate=marginRate;this.low=low;this.high=high;this.closePrice=closePrice;}
    }
    public static final class Candidate {
        public Candle open,close;
        public String side;
        public BigDecimal leverage,error;
        public Map<String,BigDecimal> calculation;
        public double score;
        public int pairs;
    }
    public static void validate(Request r,BigDecimal available) {
        if(r.side!=null && !Arrays.asList("BUY","SELL").contains(r.side))throw new BusinessException("固定方向无效");
        if(r.quantity!=null)ManualOrderCalculation.positive(r.quantity,"目标手数");
        if(r.leverage!=null) {ManualOrderCalculation.positive(r.leverage,"目标杠杆");ManualOrderCalculation.number(r.leverage,10,2,"目标杠杆");}
        if(r.percent!=null) {ManualOrderCalculation.positive(r.percent,"目标仓位比例");if(available.signum()<=0)throw new BusinessException("余额不为正，不能填写目标仓位比例");}
        if(r.targetNet!=null)ManualOrderCalculation.number(r.targetNet,32,16,"目标净收益");
        if(r.targetClosePrice!=null)ManualOrderCalculation.positive(r.targetClosePrice,"目标平仓价");
    }
    public static BigDecimal error(BigDecimal actual,BigDecimal target) {
        if(target==null)return BigDecimal.ZERO;
        if(target.signum()==0)return actual.signum()==0?BigDecimal.ZERO:HUNDRED;
        return actual.subtract(target).abs().multiply(HUNDRED).divide(target.abs(),16,RoundingMode.HALF_UP);
    }
    public static boolean withinTarget(BigDecimal actual,BigDecimal target) {
        if(target==null)return true;
        // Compare without rounding: 5.0000000000000001% must not become an accepted 5%.
        return actual.subtract(target).abs().multiply(HUNDRED).compareTo(target.abs().multiply(new BigDecimal("5")))<=0;
    }
    public static boolean matches(Request r,Map<String,BigDecimal> c,BigDecimal leverage) {
        return withinTarget(c.get("quantity"),r.quantity)
            && (r.leverage==null || leverage.compareTo(r.leverage)==0)
            && (r.percent==null || c.get("percent")!=null && withinTarget(c.get("percent"),r.percent));
    }
    private static void addRounded(Set<BigDecimal> options,BigDecimal ideal,BigDecimal step) {
        if(ideal==null || ideal.signum()<=0)return;
        BigDecimal down=ideal.divide(step,0,RoundingMode.FLOOR).multiply(step),up=ideal.divide(step,0,RoundingMode.CEILING).multiply(step);
        if(down.signum()>0)options.add(down);
        options.add(up);
    }
    private static void addTarget(Set<BigDecimal> options,BigDecimal ideal,BigDecimal step) {
        addRounded(options,ideal.multiply(LOWER),step);
        addRounded(options,ideal,step);
        addRounded(options,ideal.multiply(UPPER),step);
    }
    public static Candidate solve(Request r,NavigableMap<Long,Candle> candles,Long fixedOpen,Long fixedClose,
                                  BigDecimal available,BigDecimal lot,BigDecimal fee,long seed) {
        return solve(r,candles,fixedOpen,fixedClose,available,lot,fee,seed,STEP,STEP,BigDecimal.ZERO);
    }
    public static Candidate solve(Request r,NavigableMap<Long,Candle> candles,Long fixedOpen,Long fixedClose,
                                  BigDecimal available,BigDecimal lot,BigDecimal fee,long seed,BigDecimal step,BigDecimal minimum,BigDecimal notional) {
        return solve(r,candles,fixedOpen,fixedClose,available,lot,fee,seed,step,minimum,notional,HUNDRED);
    }
    public static Candidate solve(Request r,NavigableMap<Long,Candle> candles,Long fixedOpen,Long fixedClose,
                                  BigDecimal available,BigDecimal lot,BigDecimal fee,long seed,BigDecimal step,BigDecimal minimum,BigDecimal notional,BigDecimal configuredMax) {
        validate(r,available);
        BigDecimal max=ManualOrderCalculation.maxLeverage(configuredMax);
        if(r.leverage!=null)ManualOrderCalculation.leverage(r.leverage,max);
        if(candles.size()<1)throw new BusinessException("搜索范围没有可用分钟行情，请稍后重试或选择其他时间");
        if(fixedOpen!=null && !candles.containsKey(fixedOpen) || fixedClose!=null && !candles.containsKey(fixedClose))
            throw new BusinessException("固定分钟缺少有效开盘价或历史换算率，不使用邻近价格");
        List<Candle[]> pairs=new ArrayList<>();
        if(fixedOpen!=null && fixedClose!=null)pairs.add(new Candle[]{candles.get(fixedOpen),candles.get(fixedClose)});
        else if(fixedOpen!=null || fixedClose!=null) {
            for(Candle c:candles.values()) {
                Candle a=fixedOpen==null?c:candles.get(fixedOpen),b=fixedClose==null?c:candles.get(fixedClose);
                if(a.time<=b.time)pairs.add(new Candle[]{a,b});
            }
        } else {
            int i=0;long[] durations={5,15,30,45,75,110,170,240,360,480,720,1080,1440,2160,2880,4320};
            for(Candle a:candles.values()) {
                if(i++%30!=0)continue;
                // Same-minute orders are legal; the duration score keeps them a last resort.
                pairs.add(new Candle[]{a,a});
                for(long duration:durations)for(long variation:new long[]{-7,0,7}) {
                    if(duration+variation<=0)continue;
                    Map.Entry<Long,Candle> end=candles.ceilingEntry(a.time+(duration+variation)*MINUTE);
                    if(end!=null)pairs.add(new Candle[]{a,end.getValue()});
                }
            }
        }
        Candidate best=null,closest=null;Random random=new Random(seed);
        List<String> sides=r.side==null?Arrays.asList("BUY","SELL"):Collections.singletonList(r.side);
        Set<BigDecimal> leverages=new LinkedHashSet<>();
        if(r.leverage==null)leverages.addAll(Arrays.asList(new BigDecimal("5"),new BigDecimal("10"),new BigDecimal("20"),new BigDecimal("50"),new BigDecimal("100")));
        else leverages.add(r.leverage);
        if(r.leverage==null) {leverages.add(BigDecimal.ONE);leverages.add(max);}
        leverages.removeIf(l->l.compareTo(BigDecimal.ONE)<0 || l.compareTo(max)>0);
        for(Candle[] pair:pairs)for(String side:sides)for(BigDecimal leverage:leverages) {
            Candle a=pair[0],b=pair[1];if(a.time>b.time || !withinTarget(b.price,r.targetClosePrice))continue;
            try {
                BigDecimal unit=b.price.subtract(a.price).multiply(lot).multiply(b.rate).multiply("BUY".equals(side)?BigDecimal.ONE:BigDecimal.ONE.negate()).subtract(fee);
                BigDecimal cost=lot.multiply(a.marginRate).divide(leverage,32,RoundingMode.HALF_UP).add(fee);
                List<BigDecimal> ideals=new ArrayList<>();
                if(r.quantity!=null)ideals.add(r.quantity);
                if(r.percent!=null)ideals.add(available.multiply(r.percent).divide(HUNDRED).divide(cost,16,RoundingMode.HALF_UP));
                if(r.targetNet!=null && unit.signum()!=0)ideals.add(r.targetNet.divide(unit,16,RoundingMode.HALF_UP));
                if(ideals.isEmpty())ideals.add(available.signum()>0?available.multiply(new BigDecimal("0.15")).divide(cost,16,RoundingMode.HALF_UP):BigDecimal.ONE);
                Set<BigDecimal> quantities=new LinkedHashSet<>();
                for(BigDecimal ideal:ideals)addTarget(quantities,ideal,step);
                for(int i=0;i<ideals.size();i++)for(int j=i+1;j<ideals.size();j++)addRounded(quantities,ideals.get(i).add(ideals.get(j)).divide(new BigDecimal("2"),16,RoundingMode.HALF_UP),step);
                // Include instrument minima even when unconstrained ideals fall below them.
                addRounded(quantities,minimum,step);
                if(notional.signum()>0)addRounded(quantities,notional.divide(lot.multiply(a.price).multiply(a.rate),32,RoundingMode.CEILING),step);
                if(quantities.isEmpty())quantities.add(step);
                for(BigDecimal q:quantities) {
                    if(q.compareTo(minimum)<0 || q.multiply(lot).multiply(a.price).multiply(a.rate).compareTo(notional)<0)continue;
                    Set<BigDecimal> levels=new LinkedHashSet<>();levels.add(leverage);
                    if(r.percent!=null && r.leverage==null) {
                        BigDecimal numerator=q.multiply(lot).multiply(a.marginRate);
                        for(BigDecimal factor:new BigDecimal[]{LOWER,BigDecimal.ONE,UPPER}) {
                            BigDecimal budget=available.multiply(r.percent).multiply(factor).divide(HUNDRED).subtract(q.multiply(fee));
                            if(budget.signum()<=0)continue;
                            addRounded(levels,numerator.divide(budget,32,RoundingMode.HALF_UP),STEP);
                        }
                    }
                    for(BigDecimal l:levels)try {
                        if(l.compareTo(BigDecimal.ONE)<0 || l.compareTo(max)>0 || r.leverage!=null && l.compareTo(r.leverage)!=0)continue;
                        Map<String,BigDecimal> c=ManualOrderCalculation.calculate("QUANTITY",q,side,available,a.price,b.price,lot,l,a.rate,b.rate,fee,a.marginRate,step);
                        if(!matches(r,c,l))continue;
                        Candidate choice=new Candidate();choice.open=a;choice.close=b;choice.side=side;choice.leverage=l;choice.calculation=c;
                        choice.error=error(c.get("net"),r.targetNet);choice.pairs=pairs.size();
                        if(closest==null || choice.error.compareTo(closest.error)<0 || r.targetNet!=null && choice.error.compareTo(closest.error)==0 && c.get("net").subtract(r.targetNet).abs().compareTo(closest.calculation.get("net").subtract(r.targetNet).abs())<0)closest=choice;
                        if(!withinTarget(c.get("net"),r.targetNet))continue;
                        double quantityError=error(q,r.quantity).doubleValue(),percentError=error(c.get("percent"),r.percent).doubleValue(),netError=choice.error.doubleValue(),leverageError=error(l,r.leverage).doubleValue(),priceError=error(b.price,r.targetClosePrice).doubleValue();
                        choice.score=score((b.time-a.time)/MINUTE,c.get("percent"),l)
                            +Math.max(Math.max(leverageError,priceError),Math.max(quantityError,Math.max(percentError,netError)))*30+(quantityError+percentError+netError+leverageError+priceError)*2+random.nextDouble()*0.01;
                        if(best==null || choice.score<best.score)best=choice;
                    }catch(BusinessException | ArithmeticException ignored) { /* Invalid lot candidate. */ }
                }
            }catch(BusinessException | ArithmeticException ignored) { /* Invalid candidate, not a relaxed constraint. */ }
        }
        if(best==null)throw new BusinessException(closest==null?"固定条件冲突或搜索范围无可行解；请取消部分固定条件或更换时间范围":
            (r.targetNet!=null && r.targetNet.signum()==0?"目标净收益为 0 时必须精确匹配；本次候选最接近净收益 "+closest.calculation.get("net").stripTrailingZeros().toPlainString():
            "无法在目标净收益 ±5% 内生成；本次候选最接近净收益 "+closest.calculation.get("net").stripTrailingZeros().toPlainString()+"，误差 "+closest.error.setScale(4,RoundingMode.HALF_UP)+"%")+"；未修改目标或创建订单");
        return best;
    }
    static double score(long minutes,BigDecimal percent,BigDecimal leverage) {
        double duration=minutes<30?90+(30-minutes):minutes<60?25:minutes<120?5:minutes<=720?Math.abs(Math.log(Math.max(1,minutes)/300.0)):3+Math.log(minutes/720.0)*3;
        double allocation=percent==null?6:percent.doubleValue()<=0?20:Math.abs(Math.log(Math.max(0.000001,percent.doubleValue())/15.0))*3;
        if(percent!=null && percent.doubleValue()>100)allocation+=10+(percent.doubleValue()-100)/50;
        return duration+allocation+Math.max(0,Math.log(leverage.doubleValue()/20))*2;
    }
}
