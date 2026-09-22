package com.gtcfesk.exchange.market;

import java.math.BigDecimal;
import java.util.Locale;

/** USD ledger; USDT retains the platform's existing 1:1 settlement convention. Rates never use controlled prices. */
public final class QuoteCurrencyConversion {
    public final String code,category;
    public final BigDecimal scale;
    private QuoteCurrencyConversion(String code,String category,BigDecimal scale){this.code=code;this.category=category;this.scale=scale;}
    public static boolean fixed(String currency){return "USD".equals(currency)||"USDT".equals(currency);}
    public static QuoteCurrencyConversion route(String currency,String source){
        if(fixed(currency))return null;
        if(currency==null)return null;
        BigDecimal scale=BigDecimal.ONE;String unit=currency;
        if("GBp".equals(unit)||"GBX".equals(unit)){unit="GBP";scale=new BigDecimal("0.01");}
        if("ZAc".equals(unit)){unit="ZAR";scale=new BigDecimal("0.01");}
        if("ILA".equals(unit)){unit="ILS";scale=new BigDecimal("0.01");}
        unit=unit.toUpperCase(Locale.ROOT);
        if(!unit.matches("[A-Z0-9]{2,16}"))return null;
        return "binance".equals(source)?new QuoteCurrencyConversion(unit+"USDT","Crypto",scale)
            :new QuoteCurrencyConversion(unit+"USD=X","Forex",scale);
    }
}
