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
    String admin,user;
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
            List<Object> rows=new ArrayList<>();for(String code:q.get("symbols").split(","))rows.add(map("symbol",code,"response",Arrays.asList(map("meta",map("regularMarketPrice",code.equals("JPYUSD=X")?(fxUnavailable?0:fxRate):100,"regularMarketTime",now/1000,"previousClose",99)))));
            result=map("spark",map("result",rows));
        }else if(path.contains("chart")) {
            String code=path.substring(path.lastIndexOf('/')+1);result=map("chart",map("result",Arrays.asList(map("meta",map("symbol",code,"currency",code.equals("USDJPY=X")?"JPY":"USD","priceHint",2),"timestamp",Arrays.asList(minute/1000),"indicators",map("quote",Arrays.asList(map("open",Arrays.asList(100),"high",Arrays.asList(101),"low",Arrays.asList(99),"close",Arrays.asList(100),"volume",Arrays.asList(1))))))));
        }else if(path.contains("klines")) result=Arrays.asList(Arrays.asList(minute,"100","101","99","100","1",now,"100"));
        else result=map("symbol",q.get("symbol"),"lastPrice","BTCUSDT".equals(q.get("symbol"))?(fxUnavailable?null:fxRate):100,"closeTime",now);
        byte[] bytes=MarketIsolationTest.json.writeValueAsBytes(result);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
    }catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}}
    JsonNode request(HttpMethod method,String path,String token,Object body){HttpHeaders h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);
        return client.exchange(URI.create("http://127.0.0.1:"+t.port+path),method,new HttpEntity<>(body,h),JsonNode.class).getBody();}
    JsonNode post(String path,String token,Object body){return request(HttpMethod.POST,path,token,body);}
    void reject(String path,Object body){assertThrows(org.springframework.web.client.HttpClientErrorException.BadRequest.class,()->post(path,user,body));}
    static void equal(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    void run() throws Exception {
        t.contracts.deleteAll();t.options.deleteAll();t.symbols.deleteAll();t.quotes.refreshSymbols();
        LoginRequest login=new LoginRequest();login.setAccount("admin");login.setPassword("123456");admin=t.context.getBean(AdminAuthService.class).login(login).getToken();
        UserAccount account=new UserAccount();account.setEmail("catalog-chain@example.invalid");account.setPasswordHash(t.context.getBean(PasswordEncoder.class).encode("catalog-test-only"));account=t.context.getBean(UserAccountRepository.class).saveAndFlush(account);
        login.setAccount(account.getEmail());login.setPassword("catalog-test-only");user=t.context.getBean(AuthService.class).login(login).getToken();
        for(String coin:Arrays.asList("CONTRACT","OPTION")){AssetAccount a=new AssetAccount();a.setUserId(account.getId());a.setCoin(coin);a.setAvailable(new BigDecimal("1000000"));t.accounts.saveAndFlush(a);}
        OptionDuration d=new OptionDuration();d.setDuration(60);d.setLabel("60s");d.setSortOrder(1);d.setEnabled(true);d.setProfitRate(new BigDecimal("0.8"));d.setLossRate(BigDecimal.ONE);d.setMinAmount(BigDecimal.ONE);d.setMaxAmount(new BigDecimal("1000"));t.context.getBean(OptionDurationRepository.class).saveAndFlush(d);
        for(String[] product:PRODUCTS){
            String code=product[2],project=product[0].equals("binance")?"US":"Crypto";
            JsonNode added=post("/api/admin/symbols/catalog/add",admin,map("source",product[0],"sourceCategory",product[1],"projectCategory",project,"symbols",Arrays.asList(code)));assertEquals(1,added.path("added").size());
            TradingSymbol symbol=t.symbols.findBySymbol(code).get();assertNotNull(symbol.getIconUrl());
            ResponseEntity<String> icon=client.getForEntity(URI.create("http://127.0.0.1:"+t.port+"/api"+symbol.getIconUrl()),String.class);assertEquals(200,icon.getStatusCodeValue());assertTrue(icon.getBody().contains("<svg"));
            symbol.setMaxLeverage(new BigDecimal("20"));symbol.setLotSize(new BigDecimal("10"));symbol.setFeeMultiplier(new BigDecimal("2"));
            post("/api/admin/symbols/update",admin,symbol);MarketIsolationTest.until(()->t.quotes.freshPrice(code)!=null,12000);
            assertEquals(project,t.symbols.findById(symbol.getId()).get().getCategory());assertEquals(product[1],t.symbols.findById(symbol.getId()).get().getSourceCategory());
            Map<String,Object> order=map("symbol",code,"side","BUY","type","MARKET","quantity",2,"leverage",21,"currentPrice",9999);
            reject("/api/trade/contract/order",order);order.put("leverage",10);
            long id=post("/api/trade/contract/order",user,order).path("orderId").asLong();ContractOrder opened=t.contracts.findById(id).get();equal("100",opened.getOpenPrice());equal("200",opened.getMargin());equal("4",opened.getFee());equal("10",opened.getLeverage());
            post("/api/trade/contract/order/"+id+"/close",user,map("closePrice",9999));assertEquals("CLOSED",t.contracts.findById(id).get().getStatus());
            order.put("type","LIMIT");order.put("price",50);long pending=post("/api/trade/contract/order",user,order).path("orderId").asLong();assertEquals("PENDING",t.contracts.findById(pending).get().getStatus());
            post("/api/trade/contract/order/"+pending+"/cancel",user,null);assertEquals("CANCELLED",t.contracts.findById(pending).get().getStatus());
            order.put("price",110);long fill=post("/api/trade/contract/order",user,order).path("orderId").asLong();t.contractService.matchPendingLimitOrders();MarketIsolationTest.until(()->"OPEN".equals(t.contracts.findById(fill).get().getStatus()),4000);equal("100",t.contracts.findById(fill).get().getOpenPrice());post("/api/trade/contract/order/"+fill+"/close",user,null);
            String control="/api/admin/ai-control/"+symbol.getId();post(control+"/manual",admin,map("enabled",true,"offset",5));MarketIsolationTest.until(()->new BigDecimal("105").compareTo(t.quotes.freshPrice(code))==0,5000);
            order.put("type","MARKET");long controlled=post("/api/trade/contract/order",user,order).path("orderId").asLong();equal("105",t.contracts.findById(controlled).get().getOpenPrice());post("/api/trade/contract/order/"+controlled+"/close",user,null);
            long option=post("/api/trade/option/order",user,map("symbol",code,"direction","UP","amount",10,"duration",60,"currentPrice",999)).path("orderId").asLong();equal("105",t.options.findById(option).get().getOpenPrice());post("/api/trade/option/order/"+option+"/close",user,map("closePrice",9999));assertEquals("CLOSED",t.options.findById(option).get().getStatus());
            post(control+"/manual",admin,map("enabled",false,"offset",0));
            for(String interval:Arrays.asList("1m","5m","15m","30m","1h","1d"))MarketIsolationTest.until(()->"available".equals(t.quotes.internalKline(code,interval,1).get("status")),15000);
            String encoded=URLEncoder.encode(code,"UTF-8");JsonNode chart=request(HttpMethod.GET,"/api/market/kline/"+encoded+"?interval=1m&limit=1",null,null);assertEquals(200,chart.path("ret").asInt());
            post(control+"/start",admin,map("durationSeconds",1,"targetPrice",110,"intensity",1,"randomOscillation",false,"requestKey",UUID.randomUUID().toString()));
            MarketIsolationTest.until(()->new BigDecimal("110").compareTo(t.quotes.freshPrice(code))==0,6000);
            post(control+"/restore",admin,map("durationSeconds",1,"intensity",1,"randomOscillation",false,"requestKey",UUID.randomUUID().toString()));
            MarketIsolationTest.until(()->new BigDecimal("100").compareTo(t.quotes.freshPrice(code))==0,6000);
            symbol=t.symbols.findBySymbol(code).get();symbol.setIsEnabled(false);post("/api/admin/symbols/update",admin,symbol);reject("/api/trade/contract/order",order);symbol.setIsEnabled(true);post("/api/admin/symbols/update",admin,symbol);
            System.out.println("CATALOG_CHAIN PASS "+product[0]+"/"+product[1]+"/"+code+": independent settings, 21x rejected, 10x accepted, market/limit/fill/cancel/close, option, manual/target/restore control, six chart intervals, disabled rejection");
        }
        categoryLeverage();
        foreignCurrency();
        equal("0",t.accounts.findByUserIdAndCoin(account.getId(),"CONTRACT").get().getFrozen());equal("0",t.accounts.findByUserIdAndCoin(account.getId(),"OPTION").get().getFrozen());
    }
    void foreignCurrency() throws Exception {
        for(String[] item:new String[][]{{"binance","Crypto","ETHBTC","BTC"},{"yahoo","Forex","USDJPY=X","JPY"}}){
            String code=item[2];fxRate=100;fxUnavailable=false;
            post("/api/admin/symbols/catalog/add",admin,map("source",item[0],"sourceCategory",item[1],"projectCategory","Crypto","symbols",Arrays.asList(code)));
            TradingSymbol symbol=t.symbols.findBySymbol(code).get();symbol.setLotSize(BigDecimal.ONE);symbol.setFeeMultiplier(BigDecimal.ZERO);symbol.setMaxLeverage(BigDecimal.TEN);post("/api/admin/symbols/update",admin,symbol);
            MarketIsolationTest.until(()->t.quotes.freshPrice(code)!=null&&Boolean.TRUE.equals(t.quotes.conversion(item[3],item[0]).get("conversionAvailable")),12000);
            Map<String,Object> order=map("symbol",code,"side","BUY","type","MARKET","quantity",2,"leverage",10);
            long id=post("/api/trade/contract/order",user,order).path("orderId").asLong();equal("2000",t.contracts.findById(id).get().getMargin());equal("100",t.contracts.findById(id).get().getMarginConversionRate());
            // Controlling a visible USD conversion pair must never change the settlement rate.
            TradingSymbol btc=t.symbols.findBySymbol("BTCUSDT").get();post("/api/admin/ai-control/"+btc.getId()+"/manual",admin,map("enabled",true,"offset",5));equal("100",t.quotes.requireConversionRate(item[3],item[0]));post("/api/admin/ai-control/"+btc.getId()+"/manual",admin,map("enabled",false,"offset",0));
            order.put("type","LIMIT");order.put("price",50);long pending=post("/api/trade/contract/order",user,order).path("orderId").asLong();equal("1000",t.contracts.findById(pending).get().getMargin());
            fxUnavailable=true;
            assertTrue(Boolean.TRUE.equals(t.quotes.conversion(item[3],item[0]).get("conversionAvailable")));
            equal("100",t.quotes.requireConversionRate(item[3],item[0]));
            post("/api/trade/contract/order/"+pending+"/cancel",user,null);
            fxRate=200;fxUnavailable=false;
            // A provider update must not change the fixed settlement snapshot before expiry.
            equal("100",t.quotes.requireConversionRate(item[3],item[0]));
            order.put("type","MARKET");
            post("/api/admin/ai-control/"+symbol.getId()+"/manual",admin,map("enabled",true,"offset",5));MarketIsolationTest.until(()->new BigDecimal("105").compareTo(t.quotes.freshPrice(code))==0,5000);
            post("/api/trade/contract/order/"+id+"/close",user,null);ContractOrder settled=t.contracts.findById(id).get();equal("1000",settled.getProfit());equal("100",settled.getSettlementConversionRate());equal("2000",settled.getMargin());
            order.put("side","SELL");long sell=post("/api/trade/contract/order",user,order).path("orderId").asLong();
            ContractOrder shortOrder=t.contracts.findById(sell).get();shortOrder.setTakeProfit(new BigDecimal("101"));t.contracts.saveAndFlush(shortOrder);
            post("/api/admin/ai-control/"+symbol.getId()+"/manual",admin,map("enabled",false,"offset",0));
            MarketIsolationTest.until(()->new BigDecimal("100").compareTo(t.quotes.freshPrice(code))==0,5000);
            t.contractService.checkAndAutoCloseOrders(code,null);MarketIsolationTest.until(()->"CLOSED".equals(t.contracts.findById(sell).get().getStatus()),5000);equal("1000",t.contracts.findById(sell).get().getProfit());
            assertEquals(item[3],settled.getQuoteCurrency());assertEquals(item[0],settled.getQuoteSource());
            post("/api/admin/ai-control/"+symbol.getId()+"/manual",admin,map("enabled",false,"offset",0));
            System.out.println("FX_CHAIN PASS "+code+": USD reserve, fresh settlement rate, raw uncontrolled conversion, missing FX rejects open/close, cancellation releases reserves");
        }
    }

    void categoryLeverage() throws Exception {
        com.gtcfesk.exchange.market.MarketCategoryService service=t.context.getBean(com.gtcfesk.exchange.market.MarketCategoryService.class);
        TradingSymbol symbol=t.symbols.findBySymbol("BTCUSDT").get();
        Map<String,Object> order=map("symbol","BTCUSDT","side","BUY","type","LIMIT","quantity",1,"leverage",10,"price",50);
        long pending=post("/api/trade/contract/order",user,order).path("orderId").asLong();
        List<Map<String,Object>> categories=service.all();categories.stream().filter(row->symbol.getCategory().equals(row.get("key"))).forEach(row->row.put("leverageEnabled",false));
        post("/api/admin/symbols/categories",admin,categories);
        order.put("type","MARKET");reject("/api/trade/contract/order",order);
        order.remove("leverage");long id=post("/api/trade/contract/order",user,order).path("orderId").asLong();equal("1",t.contracts.findById(id).get().getLeverage());equal("1000",t.contracts.findById(id).get().getMargin());
        post("/api/trade/contract/order/"+id+"/close",user,null);
        ContractOrder waiting=t.contracts.findById(pending).get();waiting.setPrice(new BigDecimal("110"));t.contracts.saveAndFlush(waiting);
        t.contractService.matchPendingLimitOrders();assertEquals("PENDING",t.contracts.findById(pending).get().getStatus());post("/api/trade/contract/order/"+pending+"/cancel",user,null);
        JsonNode list=request(HttpMethod.GET,"/api/market/all",null,null).path("list");
        for(JsonNode row:list) if(row.path("symbol").asText().equals("BTCUSDT")) assertFalse(row.path("leverageEnabled").asBoolean());
        categories.stream().forEach(row->row.put("leverageEnabled",true));post("/api/admin/symbols/categories",admin,categories);
        order.put("leverage",10);long restored=post("/api/trade/contract/order",user,order).path("orderId").asLong();equal("10",t.contracts.findById(restored).get().getLeverage());post("/api/trade/contract/order/"+restored+"/close",user,null);
        System.out.println("CATEGORY_LEVERAGE PASS: disabled defaults 1x, rejects higher leverage, blocks pending fill, permits cancel/close, restores per-symbol cap");
    }

}
