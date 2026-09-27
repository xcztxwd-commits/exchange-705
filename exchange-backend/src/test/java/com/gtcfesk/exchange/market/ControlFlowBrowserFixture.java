package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.AdminAiControlController;
import com.sun.net.httpserver.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.http.HttpMethod;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in browser harness: loopback only, isolated H2, mock symbol repository/Redis, no real accounts. */
public final class ControlFlowBrowserFixture {
    public static void main(String[] args) throws Exception {
        PriceControlTest marketFixture = new PriceControlTest(); marketFixture.setup();
        ControlRecoveryFlowTest data = new ControlRecoveryFlowTest(); data.setup();
        ForexQuoteMarketService market = marketFixture.market;
        marketFixture.saved.get().setIsEnabled(true); marketFixture.saved.get().setPricePrecision(2);
        marketFixture.saved.get().setQuoteCurrency("USD");
        ReflectionTestUtils.setField(market, "controls", data.controls);
        ReflectionTestUtils.setField(market, "controlHistory", data.store);
        ReflectionTestUtils.setField(market, "klineMerger", data.merger);
        ReflectionTestUtils.setField(market, "virtualTrading", true);
        market.refreshSymbols();
        AdminAiControlController controller = new AdminAiControlController();
        ReflectionTestUtils.setField(controller, "market", market);
        ReflectionTestUtils.setField(controller, "controls", data.controls);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        ObjectMapper json = new ObjectMapper();
        ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
        clock.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis(); marketFixture.raw(90);
                data.controls.sourceQuote(marketFixture.saved.get(), data.raw(now, true), now);
                data.seed(now/60000*60000);
                market.completeControls();
            } catch (Throwable e) { System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage()); }
        }, 0, 1, TimeUnit.SECONDS);
        Path root = Paths.get(args[0]).toAbsolutePath().normalize();
        long initialMinute = System.currentTimeMillis()/60000*60000;
        for (int i=0; i<120; i++) data.seed(initialMinute-i*60000);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", args.length > 1 ? Integer.parseInt(args[1]) : 18085), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath(); byte[] body; int status = 200;
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
                if ("OPTIONS".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(204,-1); return; }
                String type = "application/json; charset=utf-8";
                if (path.equals("/api/admin/auth/login")) {
                    body = json.writeValueAsBytes(Collections.singletonMap("message", "Use /fixture-entry for isolated test login"));
                } else if (path.equals("/fixture-entry")) {
                    type = "text/html; charset=utf-8";
                    body = ("<script>localStorage.setItem('admin_token','isolated-fixture-only');localStorage.setItem('admin_user',JSON.stringify({account:'fixture',userType:'SUPER_ADMIN',permissions:['*']}));location.href='/ai-control'</script>").getBytes(StandardCharsets.UTF_8);
                } else if (path.startsWith("/api/admin/ai-control")) {
                    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.getRequestMethod()), exchange.getRequestURI()).contentType("application/json");
                    java.io.ByteArrayOutputStream input = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                    while ((n=exchange.getRequestBody().read(buffer))>=0) input.write(buffer,0,n);
                    request.content(input.toByteArray());
                    org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
                    status = response.getStatus(); body = response.getContentAsByteArray();
                } else if (path.equals("/api/fixture-quote")) {
                    body = json.writeValueAsBytes(market.internalPrice("TEST"));
                } else if (path.startsWith("/api/market/kline/") || path.startsWith("/api/market/redis/kline/")) {
                    Map<String,String> query = new HashMap<>();
                    String q = exchange.getRequestURI().getQuery();
                    if(q!=null) for(String pair:q.split("&")) { String[] parts=pair.split("=",2); if(parts.length==2)query.put(parts[0],parts[1]); }
                    String interval=query.getOrDefault("interval","1m");
                    long end=query.containsKey("endTime")?Long.parseLong(query.get("endTime")):System.currentTimeMillis();
                    long width=RandomMarketPath.duration(interval);
                    Map<Long,List<Map<String,Object>>> buckets=new TreeMap<>();
                    for(Map<String,Object> bar:data.store.candles(1,"1m",end-200*width,end)) buckets.computeIfAbsent(ControlHistoryStore.time(bar)/width*width,k->new ArrayList<>()).add(bar);
                    List<Map<String,Object>> candles=new ArrayList<>();
                    for(Map.Entry<Long,List<Map<String,Object>>> bucket:buckets.entrySet()) candles.add(ControlledKlineMerger.aggregate(bucket.getKey(),bucket.getValue()));
                    Map<String,Object> payload=new HashMap<>();payload.put("kline_list",candles);payload.put("status","available");
                    Map<String,Object> result=new HashMap<>();result.put("ret",200);result.put("data",payload);
                    body=json.writeValueAsBytes(data.merger.merge(1,interval,200,end,result,null));
                } else if (path.startsWith("/api/")) {
                    body = "[]".getBytes(StandardCharsets.UTF_8);
                } else {
                    Path file = root.resolve(path.substring(1)).normalize();
                    if (!file.startsWith(root)) throw new IllegalArgumentException("Invalid path");
                    if (!Files.isRegularFile(file)) file = root.resolve("index.html");
                    type = file.toString().endsWith(".js") ? "text/javascript" : file.toString().endsWith(".css") ? "text/css" : "text/html; charset=utf-8";
                    body = Files.readAllBytes(file);
                }
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
            } catch (Throwable e) {
                byte[] body = json.writeValueAsBytes(Collections.singletonMap("message", String.valueOf(e.getMessage())));
                exchange.sendResponseHeaders(400, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.setExecutor(Executors.newFixedThreadPool(4)); server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); clock.shutdownNow(); marketFixture.stop(); }));
        System.out.println("ISOLATED_BROWSER_FIXTURE_READY http://127.0.0.1:18085/fixture-entry");
    }
}
