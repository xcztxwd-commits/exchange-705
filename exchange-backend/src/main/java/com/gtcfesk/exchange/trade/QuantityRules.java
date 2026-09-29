package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.TradeValidation;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.entity.ContractOrder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Input-unit rules only; valuation and fixed round-trip settlement remain unchanged. */
public final class QuantityRules {
    private QuantityRules() { }
    public static boolean configured(TradingSymbol s) { return s.getQuantityUnitType()!=null; }
    public static void configuration(TradingSymbol s) {
        if (!configured(s)) {
            if(s.getSpecVersion()!=null || s.getMinOrderQuantity()!=null || s.getQuantityStep()!=null || s.getMinOrderNotional()!=null)
                throw new BusinessException("数量规格必须完整配置");
            return;
        }
        String unit=s.getQuantityUnitType();
        if (!"LOT".equals(unit) && !"BASE_ASSET".equals(unit) && !"SHARE".equals(unit)) throw new BusinessException("数量单位无效");
        TradeValidation.positive(s.getLotSize(),"数量乘数");
        if (!"LOT".equals(unit) && s.getLotSize().compareTo(BigDecimal.ONE)!=0) throw new BusinessException("币/股数量乘数必须为1");
        if (FxContractRules.isForex(s) && !"LOT".equals(unit)) throw new BusinessException("外汇必须使用标准手");
        if(s.getBaseCurrency()==null || s.getBaseCurrency().trim().isEmpty()) throw new BusinessException("基础资产缺失");
        TradeValidation.positive(s.getMinOrderQuantity(),"最小数量");
        TradeValidation.positive(s.getQuantityStep(),"数量步长");
        if(s.getMinOrderQuantity().remainder(s.getQuantityStep()).signum()!=0) throw new BusinessException("最小数量必须为步长整数倍");
        nonnegative(s.getMinOrderNotional(),"最低名义金额");
        nonnegative(s.getFeeMultiplier(),"往返佣金");
        if(s.getSpecVersion()==null || s.getSpecVersion()<=0) throw new BusinessException("规格版本无效");
        // A minimum-size fee must be exactly representable, never silently become free.
        try { s.getQuantityStep().multiply(s.getFeeMultiplier()).setScale(16,RoundingMode.UNNECESSARY); }
        catch(ArithmeticException e) { throw new BusinessException("数量步长对应手续费超出账务精度"); }
    }
    private static void nonnegative(BigDecimal v,String label) {
        if(v==null || v.signum()<0 || v.scale()>16 || v.precision()-v.scale()>16) throw new BusinessException(label+"无效");
    }
    public static void protocol(TradingSymbol s,Long version,String unit) {
        configuration(s);
        if(configured(s) && (!Objects.equals(s.getSpecVersion(),version) || !Objects.equals(s.getQuantityUnitType(),unit)))
            throw new BusinessException("数量单位或规格版本已变化，请刷新并重新确认");
        if(!configured(s) && (version!=null || unit!=null && !"LOT".equals(unit)))
            throw new BusinessException("数量规格不匹配，请刷新");
    }
    public static void quantity(TradingSymbol s,BigDecimal q) {
        if(!configured(s)) {TradeValidation.contractLots(q);return;}
        configuration(s);
        quantity(q,s.getMinOrderQuantity(),s.getQuantityStep());
    }
    public static void quantity(BigDecimal q,BigDecimal min,BigDecimal step) {
        TradeValidation.positive(q,"数量");
        if(q.compareTo(min)<0 || q.remainder(step).signum()!=0) throw new BusinessException("数量小于下限或不是步长整数倍");
    }
    public static void notional(BigDecimal q,BigDecimal lot,BigDecimal price,BigDecimal rate,BigDecimal minimum) {
        if(minimum==null)return; // Legacy snapshots have no new minimum.
        TradeValidation.positive(price,"价格");TradeValidation.positive(rate,"换汇率");
        BigDecimal value=q.multiply(lot).multiply(price).multiply(rate);
        if(value.precision()-value.scale()>16) throw new BusinessException("名义金额超出支持范围");
        if(value.compareTo(minimum)<0) throw new BusinessException("名义金额低于最低USD要求："+minimum.toPlainString());
    }
    public static void snapshot(TradingSymbol s,ContractOrder o) {
        if(!configured(s))return;
        o.setQuantityUnitType(s.getQuantityUnitType());o.setQuantityAsset(s.getBaseCurrency());o.setSpecVersion(s.getSpecVersion());
        o.setMinOrderQuantity(s.getMinOrderQuantity());o.setQuantityStep(s.getQuantityStep());o.setMinOrderNotional(s.getMinOrderNotional());
    }
    public static String signature(TradingSymbol s) {
        return s.getQuantityUnitType()+"|"+decimal(s.getMinOrderQuantity())+"|"+decimal(s.getQuantityStep())+"|"+decimal(s.getMinOrderNotional())
            +"|"+decimal(s.getLotSize())+"|"+decimal(s.getFeeMultiplier())+"|"+decimal(s.getMaxLeverage());
    }
    private static String decimal(BigDecimal v) {return v==null?"null":v.stripTrailingZeros().toPlainString();}
}
