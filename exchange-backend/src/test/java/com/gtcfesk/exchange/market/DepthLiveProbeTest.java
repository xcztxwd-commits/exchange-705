package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;

/** Opt-in anonymous public probe, never a required-network unit test and never cross-provider failover. */
class DepthLiveProbeTest {
    @Test @EnabledIfSystemProperty(named="depth.liveProbe",matches="true")
    void observeSelectedOfficialProviderAndReconnect()throws Exception{
        String provider=System.getProperty("depth.liveProvider","binance");
        ExchangeDepthSource source=new ExchangeDepthSource();source.exchange=new ExchangeQuoteSource();source.exchange.provider=provider;source.http=new MarketHttp();source.validate();
        ExchangeDepthStream stream=new ExchangeDepthStream();stream.source=source;
        Map<String,Object> report=new LinkedHashMap<>();report.put("provider",provider);report.put("startedAt",java.time.Instant.now().toString());report.put("anonymous",true);report.put("crossProviderFallback",false);
        List<Map<String,Object>> rest=new ArrayList<>();List<ExchangeDepthSource.Spec> specs=new ArrayList<>();ExchangeDepthSource.Spec next=null;
        try{
            for(String type:Arrays.asList("spot","swap")){
                String symbol="okx".equals(provider)?"BTC-USDT"+("swap".equals(type)?"-SWAP":""):"BTCUSDT";Map<String,Object> result=new LinkedHashMap<>();result.put("marketType",type);result.put("externalSymbol",symbol);long before=System.currentTimeMillis();
                try{ExchangeDepthSource.Spec spec=source.spec(symbol,type);specs.add(spec);result.put("catalogVerified",true);result.putAll(spec.view());result.put("snapshot",source.snapshot(spec).view(20));result.put("success",true);}
                catch(RuntimeException e){result.put("success",false);result.put("reason",e.getMessage());
                    try{String path="okx".equals(provider)?"/api/v5/public/instruments?instType="+("swap".equals(type)?"SWAP":"SPOT"):"swap".equals(type)?"/fapi/v1/exchangeInfo":"/api/v3/exchangeInfo";
                        String body=source.http.get(java.net.URI.create(source.base(type)+path),8*1024*1024).getBody();result.put("diagnosticBodyBytes",body.length());result.put("diagnosticPrefix",body.substring(0,Math.min(128,body.length())));
                    }catch(RuntimeException diagnostic){result.put("diagnosticReason",diagnostic.getMessage());}
                }result.put("elapsedMs",System.currentTimeMillis()-before);rest.add(result);
            }
            try{next=source.spec("okx".equals(provider)?"ETH-USDT":"ETHUSDT","spot");}catch(RuntimeException e){report.put("switchSetupError",e.getMessage());}
            report.put("rest",rest);stream.subscriptions(specs);stream.start();long start=System.nanoTime();boolean reconnected=false,switched=false;Map<String,String> sequence=new HashMap<>();Map<String,Integer> changes=new HashMap<>();Map<String,Object> samples=new LinkedHashMap<>();
            while((System.nanoTime()-start)/1000000<65000){long elapsed=(System.nanoTime()-start)/1000000;
                if(!reconnected&&elapsed>=25000&&!specs.isEmpty()){stream.lanes.get(specs.get(0).marketType).fail("acceptance_forced_reconnect");reconnected=true;}
                if(!switched&&elapsed>=45000&&next!=null){List<ExchangeDepthSource.Spec> selected=new ArrayList<>();for(ExchangeDepthSource.Spec s:specs)if(!"spot".equals(s.marketType))selected.add(s);selected.add(next);stream.subscriptions(selected);specs=selected;switched=true;}
                for(ExchangeDepthSource.Spec spec:specs){DepthBook book=stream.latest(spec);if(book!=null){String key=spec.key();if(!book.sequence.equals(sequence.put(key,book.sequence)))changes.put(key,changes.getOrDefault(key,0)+1);samples.put(key,book.view(20));}}
                Thread.sleep(250);
            }
            report.put("observationMs",(System.nanoTime()-start)/1000000);report.put("observedDistinctSequences",changes);report.put("forcedReconnect",reconnected);report.put("switchedToEth",switched);report.put("lastSnapshots",samples);report.put("spot",stream.status("spot"));report.put("swap",stream.status("swap"));
            report.put("oldSpotStillCached",stream.lanes.get("spot").latest.containsKey("okx".equals(provider)?"BTC-USDT":"BTCUSDT"));
        }finally{stream.stop();source.http.stop();report.put("finishedAt",java.time.Instant.now().toString());Path file=Paths.get("../reports/depth-20261003/live-"+provider+".json");Files.createDirectories(file.getParent());ExchangeQuoteSource.JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),report);}
    }
}
