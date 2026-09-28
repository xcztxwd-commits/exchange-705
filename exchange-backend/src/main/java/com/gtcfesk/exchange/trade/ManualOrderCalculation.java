package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import java.math.*;
import java.time.*;
import java.util.*;

/** Pure manual-order arithmetic. Normal trading limits deliberately remain elsewhere. */
public final class ManualOrderCalculation {
    private ManualOrderCalculation() {}
    public static BigDecimal number(BigDecimal v, int precision, int scale, String name) {
        if (v == null || v.stripTrailingZeros().scale() > scale || v.precision() - v.scale() > precision - scale)
            throw new BusinessException(name + "精度或范围无效");
        return v;
    }
    public static BigDecimal positive(BigDecimal v, String name) {
        number(v,32,16,name);
        if (v.signum() <= 0) throw new BusinessException(name + "必须大于零");
        return v;
    }
    public static BigDecimal money(BigDecimal v) {
        return number(v.setScale(16,RoundingMode.HALF_UP),32,16,"金额");
    }
    public static long minute(String local, String zone, String offset) {
        try {
            LocalDateTime t=LocalDateTime.parse(local);
            if(t.getSecond()!=0 || t.getNano()!=0) throw new BusinessException("时间必须精确到分钟");
            List<ZoneOffset> offsets=ZoneId.of(zone).getRules().getValidOffsets(t);
            if(offsets.isEmpty()) throw new BusinessException("所选时区不存在这个本地时间");
            if(offsets.size()>1 && (offset==null || offset.isEmpty())) throw new BusinessException("夏令时重复时间必须选择 UTC 偏移");
            ZoneOffset selected=offset==null || offset.isEmpty()?offsets.get(0):ZoneOffset.of(offset);
            if(!offsets.contains(selected)) throw new BusinessException("UTC 偏移与所选时间不符");
            return t.toInstant(selected).toEpochMilli();
        } catch(DateTimeException | NullPointerException e) {throw new BusinessException("时区或分钟格式无效");}
    }
    public static Map<String,BigDecimal> calculate(String driver, BigDecimal input, String side, BigDecimal available,
            BigDecimal p0, BigDecimal p1, BigDecimal lot, BigDecimal leverage, BigDecimal r0, BigDecimal r1, BigDecimal feePerLot) {
        positive(p0,"开仓价");positive(p1,"平仓价");positive(lot,"每手数量");positive(r0,"开仓汇率");positive(r1,"平仓汇率");
        positive(leverage,"杠杆");number(leverage,10,2,"杠杆");number(input,32,16,"输入");number(feePerLot,32,16,"手续费");
        if(feePerLot.signum()<0 || !("BUY".equals(side)||"SELL".equals(side))) throw new BusinessException("方向或手续费无效");
        BigDecimal unitMargin=lot.multiply(p0).multiply(r0).divide(leverage,32,RoundingMode.HALF_UP);
        BigDecimal unitGross=p1.subtract(p0).multiply(lot).multiply(r1).multiply("BUY".equals(side)?BigDecimal.ONE:BigDecimal.ONE.negate());
        BigDecimal unitNet=unitGross.subtract(feePerLot), q;
        switch(driver==null?"":driver) {
            case "QUANTITY": q=number(input,32,2,"手数（步长 0.01）");break;
            case "PERCENT":
                if(available.signum()<=0) throw new BusinessException("可用余额不为正，不能按仓位比例计算");
                positive(input,"仓位比例");
                q=available.multiply(input).divide(new BigDecimal("100")).divide(unitMargin.add(feePerLot),2,RoundingMode.HALF_UP);break;
            case "NET":
                if(unitNet.signum()==0) throw new BusinessException("单位净收益为零，无唯一反算解");
                q=input.divide(unitNet,2,RoundingMode.HALF_UP);break;
            default: throw new BusinessException("请选择输入驱动方式");
        }
        positive(q,"手数（目标无正手数解或小于步长）");
        BigDecimal margin=money(q.multiply(lot).multiply(p0).multiply(r0).divide(leverage,16,RoundingMode.CEILING));
        BigDecimal fee=money(q.multiply(feePerLot)),gross=money(unitGross.multiply(q)),net=money(gross.subtract(fee));
        Map<String,BigDecimal> result=new LinkedHashMap<>();
        result.put("quantity",q);result.put("margin",margin);result.put("fee",fee);result.put("profit",gross);result.put("net",net);
        result.put("percent",available.signum()>0?margin.add(fee).multiply(new BigDecimal("100")).divide(available,8,RoundingMode.HALF_UP):null);
        result.put("difference","NET".equals(driver)?money(net.subtract(input)):BigDecimal.ZERO);
        return result;
    }
}
