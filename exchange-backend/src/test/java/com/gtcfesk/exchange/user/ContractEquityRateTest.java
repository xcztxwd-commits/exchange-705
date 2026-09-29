package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractEquityRateTest {
    @Test void equityPreparationUsesSameLiveConversionAsTradingNotFiatCache() {
        JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:equity_rate_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        db.execute("create table contract_order(symbol varchar(32),quote_currency varchar(16),quote_source varchar(16),status varchar(16),user_id bigint)");
        db.update("insert into contract_order values('JPY=X','JPY','yahoo','OPEN',1)");
        ForexQuoteMarketService market=mock(ForexQuoteMarketService.class);
        long now=System.currentTimeMillis();
        Map<String,Object> q=new HashMap<>(),r=new HashMap<>();
        q.put("price",160);q.put("timestamp",now);q.put("expiresAt",now+60000);q.put("available",true);
        r.put("quoteToUsdRate",new BigDecimal("0.00625"));r.put("conversionTimestamp",now);r.put("conversionExpiresAt",now+60000);r.put("conversionAvailable",true);
        when(market.snapshotPrice("JPY=X")).thenReturn(q);when(market.contractConversion("JPY","yahoo")).thenReturn(r);
        EquityValuationService.Batch batch=new EquityValuationService(db,market).prepare(Collections.singletonList(1L));
        verify(market).contractConversion("JPY","yahoo");verify(market,never()).conversion(anyString(),anyString());
        ContractOrder o=new ContractOrder();o.setSymbol("JPY=X");o.setQuoteCurrency("JPY");o.setQuoteSource("yahoo");o.setSide("BUY");
        o.setQuantity(BigDecimal.ONE);o.setLotSize(new BigDecimal("100000"));o.setOpenPrice(new BigDecimal("157"));o.setFee(new BigDecimal("7"));
        long at=System.currentTimeMillis();EquityValuationService.Value v=new EquityValuationService.Value(1,at);
        EquityValuationService.contract(v,o,batch,at,60000);
        assertEquals(0,new BigDecimal("1875").compareTo(v.amounts.get("contract_unrealized_pnl")));assertTrue(v.reasons.isEmpty());
        r.put("conversionExpiresAt",at-1);batch.rates.put("JPY|yahoo",r);
        EquityValuationService.Value stale=new EquityValuationService.Value(1,at);EquityValuationService.contract(stale,o,batch,at,60000);
        assertNull(stale.amounts.get("contract_unrealized_pnl"));assertTrue(stale.reasons.contains("FX_UNAVAILABLE_OR_STALE"));
    }
}
