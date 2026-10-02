package com.gtcfesk.exchange.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class ManualOrderHashTest {
    @org.junit.jupiter.api.BeforeEach void tenant(){com.gtcfesk.exchange.tenant.TenantContext.open(1L);}
    @org.junit.jupiter.api.AfterEach void clear(){com.gtcfesk.exchange.tenant.TenantContext.clear();}
    @Test void tenantIsBoundInHash(){String one=hash(request(),1);com.gtcfesk.exchange.tenant.TenantContext.clear();com.gtcfesk.exchange.tenant.TenantContext.open(2L);assertNotEquals(one,hash(request(),1));}
    final ObjectMapper json=new ObjectMapper();
    final ManualOrderService service=new ManualOrderService(null,json,null,null,null,null,null);
    ManualOrderService.Request request() {
        ManualOrderService.Request r=new ManualOrderService.Request();r.userId=1L;r.symbol="FIXTUREUSD";r.side="BUY";
        r.timezone="UTC";r.openLocal="2026-09-20T10:00";r.closeLocal="2026-09-20T14:00";r.openOffset="Z";r.closeOffset="Z";
        r.driver="QUANTITY";r.input=new BigDecimal("0.0100000000000000");r.leverage=new BigDecimal("10.00");r.targetNet=new BigDecimal("0.0000");
        r.specVersion=1L;r.quantityUnitType="BASE_ASSET";return r;
    }
    String hash(ManualOrderService.Request r,long operator){return ReflectionTestUtils.invokeMethod(service,"hash",r,operator);}
    @ParameterizedTest @ValueSource(strings={"0.01","1E-2","0.0100000000000000"})
    void equivalentDecimalRepresentations(String input) {
        ManualOrderService.Request a=request(),b=request();b.input=new BigDecimal(input);b.leverage=new BigDecimal("1E+1");b.targetNet=new BigDecimal("-0E-16");
        assertEquals(hash(a,1),hash(b,1));assertEquals(new BigDecimal("0.0100000000000000"),a.input);
    }
    @Test void browserNumberToStringRoundTrip() throws Exception {
        ManualOrderService.Request a=request();Map<String,Object> wire=json.readValue(json.writeValueAsString(a),Map.class);
        for(String name:Arrays.asList("input","leverage","targetNet"))wire.put(name,wire.get(name).toString());
        assertEquals(hash(a,1),hash(json.convertValue(wire,ManualOrderService.Request.class),1));
    }
    @Test void everyRealParameterChangeAndOperatorRemainsBound() {
        List<Consumer<ManualOrderService.Request>> changes=Arrays.asList(
            r->r.input=new BigDecimal("0.0100000000000001"),r->r.leverage=new BigDecimal("10.01"),r->r.targetNet=new BigDecimal("0.0000000000000001"),
            r->r.userId=2L,r->r.symbol="OTHER",r->r.side="SELL",r->r.driver="NET",r->r.timezone="Asia/Singapore",
            r->r.openLocal="2026-09-20T10:01",r->r.closeLocal="2026-09-20T14:01",r->r.openOffset="+00:00",r->r.closeOffset="+00:00",
            r->r.walletEnabled=true,r->r.historyEnabled=true,r->r.specVersion=2L,r->r.quantityUnitType="LOT",r->r.targetNet=null);
        String expected=hash(request(),1);
        for(Consumer<ManualOrderService.Request> change:changes){ManualOrderService.Request r=request();change.accept(r);assertNotEquals(expected,hash(r,1));}
        assertNotEquals(expected,hash(request(),2));
        ManualOrderService.Request r=request();r.previewToken="another-token";r.idempotencyKey="another-key";assertEquals(expected,hash(r,1));
    }
    @Test void decimalPrecisionMustNotBeNarrowedThroughDouble() {
        for(String field:Arrays.asList("input","targetNet")) {
            ManualOrderService.Request a=request(),b=request();
            ReflectionTestUtils.setField(a,field,new BigDecimal("1000000000000000.01"));
            ReflectionTestUtils.setField(b,field,new BigDecimal("1000000000000000.02"));
            assertNotEquals(hash(a,1),hash(b,1));
        }
    }
    @Test void normalizationDoesNotExpandUntrustedExponents() {
        ManualOrderService.Request r=request();r.input=new BigDecimal("1E+1000000000");
        assertEquals(64,hash(r,1).length());r.input=new BigDecimal("1E-1000000000");assertEquals(64,hash(r,1).length());
    }
}
