package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.auth.*;
import com.gtcfesk.exchange.auth.dto.LoginRequest;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.sun.net.httpserver.HttpExchange;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP controllers, catalog parser, MySQL, Redis, quote/control engine and order services. Only upstream market data is synthetic. */
class CatalogTradingScenario {
    static volatile boolean fxUnavailable;
    static volatile double fxRate=100;
    final MarketIsolationTest t;
    final RestTemplate client=new RestTemplate();
    String admin,user; Long userId;
    static final String[][] PRODUCTS={{"binance","Crypto","BTCUSDT"},{"binance","Metal","XAUUSDT"},{"yahoo","US","AAPL"},{"yahoo","Forex","EURUSD=X"},{"yahoo","CFD","^NDX"},{"yahoo","Oil","CL=F"}};
    CatalogTradingScenario(MarketIsolationTest t){this.t=t;}
    static Map<String,Object> map(Object... args){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)out.put(args[i].toString(),args[i+1]);return out;}
    static void reply(HttpExchange exchange){try{
        String path=exchange.getRequestURI().getPath();Map<String,String> q=new HashMap<>();
        for(String item:Optional.ofNullable(exchange.getRequestURI().getRawQuery()).orElse("").split("&")){String[] pair=item.split("=",2);if(pair.length==2)q.put(pair[0],URLDecoder.decode(pair[1],"UTF-8"));}
        long now=System.currentTimeMillis(),minute=now/60000*60000;Object result;
        if(path.contains("exchangeInfo")) {
            List<Object> rows=new ArrayList<>();for(String code:Arrays.asList("BTCUSDT","XAUUSDT","ETHBTC"))rows.add(map("symbol",code,"status","TRADING","contractType","PERPETUAL","isSpotTradingAllowed",true,"baseAsset",code.substring(0,3),"quoteAsset",code.equals("ETHBTC")?"BTC":"USDT","filters",Arrays.asList(map("filterType","PRICE_FILTER","tickSize","0.01"),map("filterType","LOT_SIZE","stepSize","0.001","minQty","0.001"))));
            result=map("symbols",rows);
        }else if(path.contains("lookup")) {
            String code=q.get("query"),type=q.get("type");result=map("finance",map("result",Arrays.asList(map("documents",Arrays.asList(map("symbol",code,"shortName",code.equals("CL=F")?"Crude Oil":code,"quoteType",type)),"lookupTotals",map(type,1)))));
        }else if(path.contains("spark")) {
            List<Object> rows=new ArrayList<>();for(String code:q.get("symbols").split(","))rows.add(map("symbol",code,"response",Arrays.asList(map("meta",map("regularMarketPrice",code.equals("JPYUSD=X")?(fxUnavailable?0:fxRate):code.equals("JPY=X")?(fxUnavailable?0:1/fxRate):100,"regularMarketTime",now/1000,"previousClose",99)))));
            result=map("spark",map("result",rows));
        }else if(path.contains("chart")) {
            String code=path.substring(path.lastIndexOf('/')+1);result=map("chart",map("result",Arrays.asList(map("meta",map("symbol",code,"currency",code.equals("USDJPY=X")?"JPY":"USD","priceHint",2),"timestamp",Arrays.asList(minute/1000),"indicators",map("quote",Arrays.asList(map("open",Arrays.asList(100),"high",Arrays.asList(101),"low",Arrays.asList(99),"close",Arrays.asList(100),"volume",Arrays.asList(1))))))));
        }else if(path.contains("ticker/24hr") && q.containsKey("symbols")) {
            List<Object> rows=new ArrayList<>();for(JsonNode symbol:MarketIsolationTest.json.readTree(q.get("symbols"))) {
                String code=symbol.asText();rows.add(map("symbol",code,"lastPrice",code.equals("BTCUSDT")?(fxUnavailable?null:fxRate):100,"closeTime",now));
            }result=rows;
        }else if(path.contains("klines")) result=Arrays.asList(Arrays.asList(minute,"100","101","99","100","1",now,"100"));
        else result=map("symbol",q.get("symbol"),"lastPrice","BTCUSDT".equals(q.get("symbol"))?(fxUnavailable?null:fxRate):100,"closeTime",now);
        byte[] bytes=MarketIsolationTest.json.writeValueAsBytes(result);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
    }catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}}
    HttpHeaders headers(String path,String token){HttpHeaders h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);h.set("X-Forwarded-Host",path.startsWith("/api/admin/")?com.gtcfesk.exchange.tenant.BootTenantFixture.ADMIN:com.gtcfesk.exchange.tenant.BootTenantFixture.FRONT);return h;}
    @SuppressWarnings("unchecked") ResponseEntity<JsonNode> exchange(HttpMethod method,String path,String token,Object body){
        if(method==HttpMethod.POST&&Arrays.asList("/api/trade/contract/order","/api/trade/option/order").contains(path)&&body instanceof Map){Map<String,Object> keyed=new LinkedHashMap<>((Map<String,Object>)body);keyed.putIfAbsent("requestId",UUID.randomUUID().toString());body=keyed;}
        return client.exchange(URI.create("http://127.0.0.1:"+t.port+path),method,new HttpEntity<>(body,headers(path,token)),JsonNode.class);}
    JsonNode request(HttpMethod method,String path,String token,Object body){return exchange(method,path,token,body).getBody();}
    JsonNode post(String path,String token,Object body){return request(HttpMethod.POST,path,token,body);}
    void reject(String path,Object body){assertThrows(org.springframework.web.client.HttpClientErrorException.BadRequest.class,()->post(path,user,body));}
    static void equal(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    boolean crypto(TradingSymbol symbol) { return "Crypto".equals(symbol.getSourceCategory()) || "CryptoPerpetual".equals(symbol.getSourceCategory()); }
    void rejectWithoutWrites(String path,String token,Object body) {
        AssetAccount before=t.accounts.findByTenantIdAndUserIdAndCoin(1L, userId,"CONTRACT").get();
        BigDecimal available=before.getAvailable(),frozen=before.getFrozen();long count=t.contracts.countByTenantId(1L);
        assertThrows(org.springframework.web.client.HttpClientErrorException.BadRequest.class,()->post(path,token,body));
        AssetAccount after=t.accounts.findByTenantIdAndUserIdAndCoin(1L, userId,"CONTRACT").get();
        equal(available.toPlainString(),after.getAvailable());equal(frozen.toPlainString(),after.getFrozen());assertEquals(count,t.contracts.countByTenantId(1L));
    }
    TradingSymbol configure(TradingSymbol symbol) {
        if(crypto(symbol)) {
            assertFalse(Boolean.TRUE.equals(symbol.getIsEnabled()),"new crypto remains disabled until reviewed");
            Map<String,Object> order=map("symbol",symbol.getSymbol(),"side","BUY","type","MARKET","quantity",2,"leverage",10);
            rejectWithoutWrites("/api/trade/contract/order",user,order);
            symbol.setIsEnabled(true);
            rejectWithoutWrites("/api/admin/symbols/update",admin,symbol);
            assertFalse(Boolean.TRUE.equals(t.symbols.findByTenantIdAndId(1L, symbol.getId()).get().getIsEnabled()));
            symbol.setQuantityUnitType("BASE_ASSET");symbol.setLotSize(BigDecimal.ONE);
            symbol.setMinOrderQuantity(new BigDecimal("0.001"));symbol.setMinOrderNotional(BigDecimal.ZERO);
            // Missing step must not partially install a specification or enable trading.
            rejectWithoutWrites("/api/admin/symbols/update",admin,symbol);
            TradingSymbol rejected=t.symbols.findByTenantIdAndId(1L, symbol.getId()).get();assertNull(rejected.getQuantityUnitType());assertFalse(Boolean.TRUE.equals(rejected.getIsEnabled()));
            symbol.setQuantityStep(new BigDecimal("0.001"));
        }
        post("/api/admin/symbols/update",admin,symbol);
        TradingSymbol saved=t.symbols.findByTenantIdAndId(1L, symbol.getId()).get();
        if(crypto(saved)) {
            assertTrue(Boolean.TRUE.equals(saved.getIsEnabled()));assertEquals("BASE_ASSET",saved.getQuantityUnitType());assertNotNull(saved.getSpecVersion());
            Map<String,Object> order=map("symbol",saved.getSymbol(),"side","BUY","type","MARKET","quantity",2,"leverage",10);
            rejectWithoutWrites("/api/trade/contract/order",user,order); // Missing protocol, even when enabled.
            order.put("specVersion",saved.getSpecVersion()-1);order.put("quantityUnitType","BASE_ASSET");
            rejectWithoutWrites("/api/trade/contract/order",user,order);
            order.put("specVersion",saved.getSpecVersion());order.put("quantityUnitType","LOT");
            rejectWithoutWrites("/api/trade/contract/order",user,order);
            System.out.println("CRYPTO_PRECONDITIONS PASS "+saved.getSymbol()+": disabled, missing/incomplete spec, missing/stale/wrong protocol rejected without funds/order writes");
        }
        return saved;
    }
    Map<String,Object> protocol(TradingSymbol symbol,Map<String,Object> order) {
        if(symbol.getQuantityUnitType()!=null) {order.put("quantityUnitType",symbol.getQuantityUnitType());order.put("specVersion",symbol.getSpecVersion());}
        return order;
    }
    JsonNode acceptedStart(String control,Map<String,Object> body){ResponseEntity<JsonNode> response=exchange(HttpMethod.POST,control+"/start",admin,body);assertEquals(202,response.getStatusCodeValue());JsonNode receipt=response.getBody();assertNotNull(receipt);assertFalse(receipt.path("commandId").asText().isEmpty());assertEquals(body.get("requestKey"),receipt.path("requestKey").asText());return receipt;}
    JsonNode command(String control,String key){return request(HttpMethod.GET,control+"/commands?requestKey="+key,admin,null);}
    void rejectedStartWithoutEffects(String control,Long symbolId,Map<String,Object> body)throws Exception{
        org.springframework.jdbc.core.JdbcTemplate db=t.context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        long tasks=db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Long.class,symbolId),version=t.symbols.findByTenantIdAndId(1L,symbolId).get().getRowVersion(),orders=t.contracts.countByTenantId(1L);
        AssetAccount before=t.accounts.findByTenantIdAndUserIdAndCoin(1L,userId,"CONTRACT").get();BigDecimal available=before.getAvailable(),frozen=before.getFrozen();
        JsonNode accepted=acceptedStart(control,body);String key=(String)body.get("requestKey");MarketIsolationTest.until(()->"FAILED".equals(command(control,key).path("state").asText()),10000);
        JsonNode failed=command(control,key);assertEquals(accepted.path("commandId"),failed.path("commandId"));assertTrue(failed.path("taskId").isNull());assertTrue(Arrays.asList("CORRIDOR_PRECISION_UNREPRESENTABLE","AMPLITUDE_PRECISION_UNREPRESENTABLE","TARGET_AMPLITUDE_INFEASIBLE").contains(failed.path("errorCode").asText()),failed.toString());
        assertEquals(tasks,db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Long.class,symbolId));assertEquals(version,t.symbols.findByTenantIdAndId(1L,symbolId).get().getRowVersion());assertEquals(orders,t.contracts.countByTenantId(1L));
        AssetAccount after=t.accounts.findByTenantIdAndUserIdAndCoin(1L,userId,"CONTRACT").get();equal(available.toPlainString(),after.getAvailable());equal(frozen.toPlainString(),after.getFrozen());
    }
    void run() throws Exception {
        // The ordered isolation fixture must finish its own order cleanup. Retain its durable source/runtime evidence.
        assertEquals(0,t.contracts.countByTenantId(1L));assertEquals(0,t.options.countByTenantId(1L));t.quotes.refreshSymbols();
        t.recovery();
        String privatePassword=UUID.randomUUID()+"-catalog-only";AdminUser owner=new AdminUser();owner.setAccount("catalog-"+UUID.randomUUID());owner.setEmail(owner.getAccount()+"@example.invalid");owner.setPasswordHash(t.context.getBean(PasswordEncoder.class).encode(privatePassword));owner.setRole("super_admin");owner.setEnabled(true);owner=t.context.getBean(AdminUserRepository.class).saveAndFlush(owner);t.context.getBean(com.gtcfesk.exchange.control.BackendLoginRegistry.class).register("ADMIN",owner.getId(),owner.getAccount());
        LoginRequest login=new LoginRequest();login.setAccount(owner.getAccount());login.setPassword(privatePassword);admin=t.context.getBean(AdminAuthService.class).login(login).getToken();
        UserAccount account=new UserAccount();account.setEmail("catalog-"+UUID.randomUUID()+"@example.invalid");account.setPasswordHash(t.context.getBean(PasswordEncoder.class).encode("catalog-test-only"));account=t.context.getBean(UserAccountRepository.class).saveAndFlush(account);
        KycRecord identity=new KycRecord();identity.setUserId(account.getId());identity.setRealName("Catalog fixture");identity.setIdNumber("TEST-ONLY");identity.setStatus("APPROVED");t.context.getBean(KycRecordRepository.class).saveAndFlush(identity);
        userId=account.getId();login.setAccount(account.getEmail());login.setPassword("catalog-test-only");user=t.context.getBean(AuthService.class).login(login).getToken();
        for(String coin:Arrays.asList("CONTRACT","OPTION")){AssetAccount a=new AssetAccount();a.setUserId(account.getId());a.setCoin(coin);a.setAvailable(new BigDecimal("1000000"));t.accounts.saveAndFlush(a);}
        OptionDuration d=new OptionDuration();d.setDuration(60);d.setLabel("60s");d.setSortOrder(1);d.setEnabled(true);d.setProfitRate(new BigDecimal("0.8"));d.setLossRate(BigDecimal.ONE);d.setMinAmount(BigDecimal.ONE);d.setMaxAmount(new BigDecimal("1000"));t.context.getBean(OptionDurationRepository.class).saveAndFlush(d);
        for(String[] product:PRODUCTS){
            String code=product[2],project=product[0].equals("binance")?"US":"Crypto";
            JsonNode added=post("/api/admin/symbols/catalog/add",admin,map("source",product[0],"sourceCategory",product[1],"projectCategory",project,"symbols",Arrays.asList(code)));assertEquals(1,added.path("added").size());
            TradingSymbol symbol=t.symbols.findByTenantIdAndSymbol(1L, code).get();assertNotNull(symbol.getIconUrl());
            ResponseEntity<String> icon=client.exchange(URI.create("http://127.0.0.1:"+t.port+"/api"+symbol.getIconUrl()),HttpMethod.GET,new HttpEntity<>(null,headers("/api"+symbol.getIconUrl(),null)),String.class);assertEquals(200,icon.getStatusCodeValue());assertTrue(icon.getBody().contains("<svg"));
            boolean forex="Forex".equals(symbol.getSourceCategory()),nativeQuantity=crypto(symbol);
            symbol.setMaxLeverage(new BigDecimal("20"));symbol.setLotSize(new BigDecimal(forex?"100000":nativeQuantity?"1":"10"));symbol.setFeeMultiplier(new BigDecimal(forex?"200":nativeQuantity?"0.2":"2"));
            symbol=configure(symbol);MarketIsolationTest.until(()->t.quotes.freshPrice(code)!=null,12000);
            assertEquals(project,t.symbols.findByTenantIdAndId(1L, symbol.getId()).get().getCategory());assertEquals(product[1],t.symbols.findByTenantIdAndId(1L, symbol.getId()).get().getSourceCategory());
            // Native crypto 20 * 1 replaces 2 * 10; FX uses legal 0.02 standard lots.
            Map<String,Object> order=protocol(symbol,map("symbol",code,"side","BUY","type","MARKET","quantity",new BigDecimal(forex?"0.02":nativeQuantity?"20":"2"),"leverage",21,"currentPrice",9999));
            reject("/api/trade/contract/order",order);order.put("leverage",10);
            long id=post("/api/trade/contract/order",user,order).path("orderId").asLong();ContractOrder opened=t.contracts.findByTenantIdAndId(1L, id).get();equal("100",opened.getOpenPrice());equal(forex?"20000":"200",opened.getMargin());equal("4",opened.getFee());equal("10",opened.getLeverage());
            post("/api/trade/contract/order/"+id+"/close",user,map("closePrice",9999));assertEquals("CLOSED",t.contracts.findByTenantIdAndId(1L, id).get().getStatus());
            order.put("type","LIMIT");order.put("price",50);long pending=post("/api/trade/contract/order",user,order).path("orderId").asLong();assertEquals("PENDING",t.contracts.findByTenantIdAndId(1L, pending).get().getStatus());
            post("/api/trade/contract/order/"+pending+"/cancel",user,null);assertEquals("CANCELLED",t.contracts.findByTenantIdAndId(1L, pending).get().getStatus());
            order.put("price",110);long fill=post("/api/trade/contract/order",user,order).path("orderId").asLong();t.contractService.matchPendingLimitOrders();MarketIsolationTest.until(()->"OPEN".equals(t.contracts.findByTenantIdAndId(1L, fill).get().getStatus()),4000);equal("100",t.contracts.findByTenantIdAndId(1L, fill).get().getOpenPrice());post("/api/trade/contract/order/"+fill+"/close",user,null);
            String control="/api/admin/ai-control/"+symbol.getId();post(control+"/manual",admin,map("enabled",true,"offset",5));MarketIsolationTest.until(()->new BigDecimal("105").compareTo(t.quotes.freshPrice(code))==0,5000);
            order.put("type","MARKET");long controlled=post("/api/trade/contract/order",user,order).path("orderId").asLong();equal("105",t.contracts.findByTenantIdAndId(1L, controlled).get().getOpenPrice());post("/api/trade/contract/order/"+controlled+"/close",user,null);
            long option=post("/api/trade/option/order",user,map("symbol",code,"direction","UP","amount",10,"duration",60,"currentPrice",999)).path("orderId").asLong();equal("105",t.options.findByTenantIdAndId(1L, option).get().getOpenPrice());post("/api/trade/option/order/"+option+"/close",user,map("closePrice",9999));assertEquals("CLOSED",t.options.findByTenantIdAndId(1L, option).get().getStatus());
            post(control+"/manual",admin,map("enabled",false,"offset",0));
            for(String interval:Arrays.asList("1m","5m","15m","30m","1h","1d"))MarketIsolationTest.until(()->"available".equals(t.quotes.internalKline(code,interval,1).get("status")),15000);
            String encoded=URLEncoder.encode(code,"UTF-8");JsonNode chart=request(HttpMethod.GET,"/api/market/kline/"+encoded+"?interval=1m&limit=1",null,null);assertEquals(200,chart.path("ret").asInt());
            // S2 accepts durably, then rejects an infeasible V4 path without activating any task or changing funds.
            rejectedStartWithoutEffects(control,symbol.getId(),map("durationSeconds",1,"targetPrice",110,"intensity",1,"randomOscillation",false,"requestKey",UUID.randomUUID().toString()));
            MarketIsolationTest.until(()->new BigDecimal("100").compareTo(t.quotes.freshPrice(code))==0,6000);
            String key=UUID.randomUUID().toString();JsonNode accepted=acceptedStart(control,map("durationSeconds",3,"targetPrice",new BigDecimal("100.01"),"intensity",10,"randomOscillation",false,"requestKey",key));
            MarketIsolationTest.until(()->"RUNNING".equals(command(control,key).path("state").asText()),10000);JsonNode activated=command(control,key);assertEquals(accepted.path("commandId"),activated.path("commandId"));assertFalse(activated.path("taskId").asText().isEmpty());
            MarketIsolationTest.until(()->new BigDecimal("100.01").compareTo(t.quotes.freshPrice(code))==0,8000);
            post(control+"/restore",admin,map("durationSeconds",3,"intensity",10,"randomOscillation",false,"requestKey",UUID.randomUUID().toString()));
            MarketIsolationTest.until(()->new BigDecimal("100").compareTo(t.quotes.freshPrice(code))==0,6000);
            symbol=t.symbols.findByTenantIdAndSymbol(1L, code).get();symbol.setIsEnabled(false);post("/api/admin/symbols/update",admin,symbol);reject("/api/trade/contract/order",order);symbol.setIsEnabled(true);post("/api/admin/symbols/update",admin,symbol);
            System.out.println("CATALOG_CHAIN PASS "+product[0]+"/"+product[1]+"/"+code+": independent settings, 21x rejected, 10x accepted, market/limit/fill/cancel/close, option, manual/target/restore control, six chart intervals, disabled rejection");
        }
        categoryLeverage();
        foreignCurrency();
        equal("0",t.accounts.findByTenantIdAndUserIdAndCoin(1L, account.getId(),"CONTRACT").get().getFrozen());equal("0",t.accounts.findByTenantIdAndUserIdAndCoin(1L, account.getId(),"OPTION").get().getFrozen());
        verifyOrderHistories();
    }
    void foreignCurrency() throws Exception {
        for(String[] item:new String[][]{{"binance","Crypto","ETHBTC","BTC"},{"yahoo","Forex","USDJPY=X","JPY"}}){
            String code=item[2];fxRate=100;fxUnavailable=false;
            post("/api/admin/symbols/catalog/add",admin,map("source",item[0],"sourceCategory",item[1],"projectCategory","Crypto","symbols",Arrays.asList(code)));
            TradingSymbol symbol=t.symbols.findByTenantIdAndSymbol(1L, code).get();boolean forex="Forex".equals(item[1]);symbol.setLotSize(new BigDecimal(forex?"100000":"1"));symbol.setFeeMultiplier(BigDecimal.ZERO);symbol.setMaxLeverage(BigDecimal.TEN);symbol=configure(symbol);
            MarketIsolationTest.until(()->t.quotes.freshPrice(code)!=null&&Boolean.TRUE.equals(t.quotes.conversion(item[3],item[0]).get("conversionAvailable")),12000);
            Map<String,Object> order=protocol(symbol,map("symbol",code,"side","BUY","type","MARKET","quantity",new BigDecimal(forex?"0.01":"2"),"leverage",10));
            long id=post("/api/trade/contract/order",user,order).path("orderId").asLong();equal(forex?"100":"2000",t.contracts.findByTenantIdAndId(1L, id).get().getMargin());equal(forex?"1":"100",t.contracts.findByTenantIdAndId(1L, id).get().getMarginConversionRate());
            // Controlling a visible USD conversion pair must never change the settlement rate.
            TradingSymbol btc=t.symbols.findByTenantIdAndSymbol(1L, "BTCUSDT").get();post("/api/admin/ai-control/"+btc.getId()+"/manual",admin,map("enabled",true,"offset",5));equal("100",t.quotes.requireConversionRate(item[3],item[0]));post("/api/admin/ai-control/"+btc.getId()+"/manual",admin,map("enabled",false,"offset",0));
            order.put("type","LIMIT");order.put("price",50);long pending=post("/api/trade/contract/order",user,order).path("orderId").asLong();equal(forex?"100":"1000",t.contracts.findByTenantIdAndId(1L, pending).get().getMargin());
            fxUnavailable=true;
            assertTrue(Boolean.TRUE.equals(t.quotes.conversion(item[3],item[0]).get("conversionAvailable")));
            equal("100",t.quotes.requireConversionRate(item[3],item[0]));
            post("/api/trade/contract/order/"+pending+"/cancel",user,null);
            fxRate=200;fxUnavailable=false;
            MarketIsolationTest.until(()-> { Object rate=t.quotes.contractConversion(item[3],item[0]).get("quoteToUsdRate");return rate instanceof BigDecimal && new BigDecimal("200").compareTo((BigDecimal)rate)==0; },12000);
            // Deposit conversion stays cached; contract settlement independently uses the live raw rate.
            equal("100",t.quotes.requireConversionRate(item[3],item[0]));
            order.put("type","MARKET");
            post("/api/admin/ai-control/"+symbol.getId()+"/manual",admin,map("enabled",true,"offset",5));MarketIsolationTest.until(()->new BigDecimal("105").compareTo(t.quotes.freshPrice(code))==0,5000);
            try { post("/api/trade/contract/order/"+id+"/close",user,null); }
            catch(org.springframework.web.client.HttpClientErrorException failure) {
                ContractOrder current=t.contracts.findByTenantIdAndId(1L, id).get();System.out.println("CLOSE_DIAGNOSTIC symbol="+code+", id="+id+", status="+current.getStatus()+", rowVersion="+current.getRowVersion()+", response="+failure.getResponseBodyAsString());throw failure;
            }ContractOrder settled=t.contracts.findByTenantIdAndId(1L, id).get();equal(forex?"1000000":"2000",settled.getProfit());equal("200",settled.getSettlementConversionRate());equal(forex?"100":"2000",settled.getMargin());
            order.put("side","SELL");long sell=post("/api/trade/contract/order",user,order).path("orderId").asLong();
            ContractOrder shortOrder=t.contracts.findByTenantIdAndId(1L, sell).get();shortOrder.setTakeProfit(new BigDecimal("101"));t.contracts.saveAndFlush(shortOrder);
            post("/api/admin/ai-control/"+symbol.getId()+"/manual",admin,map("enabled",false,"offset",0));
            MarketIsolationTest.until(()->new BigDecimal("100").compareTo(t.quotes.freshPrice(code))==0,5000);
            t.contractService.checkAndAutoCloseOrders(code,null);MarketIsolationTest.until(()->"CLOSED".equals(t.contracts.findByTenantIdAndId(1L, sell).get().getStatus()),5000);equal(forex?"1000000":"2000",t.contracts.findByTenantIdAndId(1L, sell).get().getProfit());
            assertEquals(item[3],settled.getQuoteCurrency());assertEquals(item[0],settled.getQuoteSource());
            post("/api/admin/ai-control/"+symbol.getId()+"/manual",admin,map("enabled",false,"offset",0));
            System.out.println("FX_CHAIN PASS "+code+": USD reserve, fresh settlement rate, raw uncontrolled conversion, cached FX survives transient provider failure, cancellation releases reserves");
        }
    }

    void categoryLeverage() throws Exception {
        com.gtcfesk.exchange.market.MarketCategoryService service=t.context.getBean(com.gtcfesk.exchange.market.MarketCategoryService.class);
        TradingSymbol symbol=t.symbols.findByTenantIdAndSymbol(1L, "BTCUSDT").get();
        Map<String,Object> order=protocol(symbol,map("symbol","BTCUSDT","side","BUY","type","LIMIT","quantity",10,"leverage",10,"price",50));
        long pending=post("/api/trade/contract/order",user,order).path("orderId").asLong();
        List<Map<String,Object>> categories=service.all();categories.stream().filter(row->symbol.getCategory().equals(row.get("key"))).forEach(row->row.put("leverageEnabled",false));
        post("/api/admin/symbols/categories",admin,categories);
        order.put("type","MARKET");reject("/api/trade/contract/order",order);
        order.remove("leverage");long id=post("/api/trade/contract/order",user,order).path("orderId").asLong();equal("1",t.contracts.findByTenantIdAndId(1L, id).get().getLeverage());equal("1000",t.contracts.findByTenantIdAndId(1L, id).get().getMargin());
        post("/api/trade/contract/order/"+id+"/close",user,null);
        ContractOrder waiting=t.contracts.findByTenantIdAndId(1L, pending).get();waiting.setPrice(new BigDecimal("110"));t.contracts.saveAndFlush(waiting);
        t.contractService.matchPendingLimitOrders();assertEquals("PENDING",t.contracts.findByTenantIdAndId(1L, pending).get().getStatus());post("/api/trade/contract/order/"+pending+"/cancel",user,null);
        JsonNode list=request(HttpMethod.GET,"/api/market/all",null,null).path("list");
        for(JsonNode row:list) if(row.path("symbol").asText().equals("BTCUSDT")) assertFalse(row.path("leverageEnabled").asBoolean());
        categories.stream().forEach(row->row.put("leverageEnabled",true));post("/api/admin/symbols/categories",admin,categories);
        order.put("leverage",10);long restored=post("/api/trade/contract/order",user,order).path("orderId").asLong();equal("10",t.contracts.findByTenantIdAndId(1L, restored).get().getLeverage());post("/api/trade/contract/order/"+restored+"/close",user,null);
        System.out.println("CATEGORY_LEVERAGE PASS: disabled defaults 1x, rejects higher leverage, blocks pending fill, permits cancel/close, restores per-symbol cap");
    }


    void verifyOrderHistories() {
        for (String kind : new String[]{"contract", "option"}) {
            JsonNode userRows = request(HttpMethod.GET, "/api/trade/" + kind + "/orders", user, null).path("list");
            JsonNode adminResult = post("/api/admin/orders/" + kind + "/query", admin,
                CatalogTradingScenario.map("page", 0, "size", 200));
            JsonNode adminRows = adminResult.path("list");
            long persisted = "contract".equals(kind) ? t.contracts.countByTenantId(1L) : t.options.countByTenantId(1L);
            assertTrue(persisted > 0);
            assertEquals(persisted, userRows.size(), "user history must include every persisted fixture");
            assertEquals(persisted, adminResult.path("total").asLong());
            assertEquals(persisted, adminRows.size());
            Map<Long, JsonNode> byId = new HashMap<>();
            for (JsonNode row : adminRows) byId.put(row.path("id").asLong(), row);
            int controlled = 0;
            for (JsonNode row : userRows) {
                JsonNode admin = byId.get(row.path("id").asLong());
                assertNotNull(admin);
                for (String field : new String[]{"symbol", "status", "openPrice", "closePrice", "profit", "createdAt"})
                    assertEquals(row.get(field), admin.get(field), field);
                assertFalse(row.path("createdAt").isNull());
                assertTrue("CLOSED".equals(row.path("status").asText()) || "CANCELLED".equals(row.path("status").asText()));
                if (row.path("openPrice").decimalValue().compareTo(new java.math.BigDecimal("105")) == 0) controlled++;
            }
            assertTrue(controlled >= 6, "six categories must retain controlled-price trade records");
            System.out.println("RECORDS PASS " + kind + ": database=" + persisted + ", user=" + userRows.size()
                + ", admin=" + adminRows.size() + ", controlled=" + controlled);
        }
    }
}
