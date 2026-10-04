package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import java.net.URI;
import java.util.*;

/** Anonymous official HTTPS data, only from ExchangeQuoteSource's selected provider. */
@Component
class ExchangeDepthSource {
    @Autowired ExchangeQuoteSource exchange;
    @Autowired MarketHttp http;
    @Value("${market.depth.allow-test-sources:false}") boolean allowTestSources;
    @Value("${market.depth.enabled:true}") boolean enabled=true;
    static final class Spec {
        final String externalSymbol,marketType,quantityUnit,quantityCurrency,quoteCurrency;
        final String contractValue,contractMultiplier,contractValueCurrency;
        Spec(String symbol,String type,String unit,String currency,String quote,String value,String multiplier,String valueCurrency){
            externalSymbol=symbol;marketType=type;quantityUnit=unit;quantityCurrency=currency;quoteCurrency=quote;
            contractValue=value;contractMultiplier=multiplier;contractValueCurrency=valueCurrency;
        }
        String key(){return marketType+":"+externalSymbol;}
        Map<String,Object> view(){Map<String,Object> r=new LinkedHashMap<>();r.put("externalSymbol",externalSymbol);r.put("marketType",marketType);
            r.put("quantityUnit",quantityUnit);r.put("quantityCurrency",quantityCurrency);r.put("quoteCurrency",quoteCurrency);
            r.put("contractValue",contractValue);r.put("contractMultiplier",contractMultiplier);r.put("contractValueCurrency",contractValueCurrency);return r;}
    }
    private final Map<String,JsonNode> catalogs=new LinkedHashMap<>();
    private final Map<String,Long> catalogAt=new HashMap<>();
    boolean okx(){return "okx".equals(exchange.provider);}
    String base(String type){return okx()?exchange.okxUrl:"swap".equals(type)?exchange.futuresUrl:exchange.spotUrl;}
    @PostConstruct void validate(){
        if(!enabled)return;
        if(!Arrays.asList("binance","okx").contains(exchange.provider))throw new IllegalArgumentException("Unsupported depth provider");
        if(okx())official(exchange.okxUrl,"https",Collections.singleton("www.okx.com"));
        else {official(exchange.spotUrl,"https",Arrays.asList("data-api.binance.vision","api.binance.com"));official(exchange.futuresUrl,"https",Collections.singleton("fapi.binance.com"));}
    }
    void official(String url,String scheme,Collection<String> hosts){
        URI u=URI.create(url);
        boolean test=allowTestSources&&Arrays.asList("127.0.0.1","localhost","::1").contains(u.getHost());
        if(u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||(!test&&(!scheme.equals(u.getScheme())||!hosts.contains(u.getHost())||u.getPort()!=-1&&u.getPort()!=443)))
            throw new IllegalArgumentException("Depth requires an allowed official endpoint");
    }
    JsonNode get(String url,int bytes){
        try {JsonNode root=ExchangeQuoteSource.JSON.readTree(http.get(URI.create(url),bytes).getBody());
            if(okx()){if(!"0".equals(root.path("code").asText()))throw DepthBook.invalid("provider_error");return root.path("data");}
            if(root.has("code"))throw DepthBook.invalid("provider_error");return root;
        }catch(MarketHttp.Failure e){throw e;}catch(Exception e){throw DepthBook.invalid("invalid_response");}
    }
    // ponytail: bounded official metadata cache, selected instruments where the API supports filtering.
    synchronized JsonNode catalog(String type,String external){long now=System.currentTimeMillis();boolean filtered=okx()||"spot".equals(type);
        String key=exchange.provider+":"+type+(filtered?":"+external:"");
        if(now-catalogAt.getOrDefault(key,0L)<3600000)return catalogs.get(key);
        String url=okx()?base(type)+"/api/v5/public/instruments?instType="+("swap".equals(type)?"SWAP":"SPOT")+"&instId="+external
            :base(type)+("swap".equals(type)?"/fapi/v1/exchangeInfo":"/api/v3/exchangeInfo?symbol="+external+"&showPermissionSets=false");
        JsonNode root;
        try{root=get(url,filtered?1024*1024:8*1024*1024);}
        catch(MarketHttp.Failure failure){
            // All other filters are fixed and valid; Binance rejects a nonexistent symbol with HTTP 400.
            if(!okx()&&filtered&&"http_400".equals(failure.getMessage()))throw DepthBook.invalid("unsupported_instrument");
            throw failure;
        }
        JsonNode rows=okx()?root:root.path("symbols");
        if(!rows.isArray())throw DepthBook.invalid("invalid_catalog");
        if(rows.size()==0)throw DepthBook.invalid("unsupported_instrument");
        if(catalogs.size()>=64&&!catalogs.containsKey(key)){String oldest=catalogs.keySet().iterator().next();catalogs.remove(oldest);catalogAt.remove(oldest);}
        catalogs.put(key,rows);catalogAt.put(key,now);return rows;
    }
    Spec spec(String external,String type){
        for(JsonNode row:catalog(type,external))if(external.equals(row.path(okx()?"instId":"symbol").asText())){
            if(okx()){
                if(!"live".equals(row.path("state").asText())||!("swap".equals(type)?"SWAP":"SPOT").equals(row.path("instType").asText()))throw DepthBook.invalid("unsupported_instrument");
                if("swap".equals(type)){
                    String value=DepthBook.text(DepthBook.decimal(row.path("ctVal"))),multiplier=DepthBook.text(DepthBook.decimal(row.path("ctMult")));
                    String ccy=row.path("ctValCcy").asText(),quote=row.path("settleCcy").asText();
                    if(ccy.isEmpty()||quote.isEmpty()||new java.math.BigDecimal(value).signum()<=0||new java.math.BigDecimal(multiplier).signum()<=0)throw DepthBook.invalid("invalid_contract_spec");
                    return new Spec(external,type,"CONTRACT","CONTRACT",quote,value,multiplier,ccy);
                }
                String base=row.path("baseCcy").asText(),quote=row.path("quoteCcy").asText();
                if(base.isEmpty()||quote.isEmpty())throw DepthBook.invalid("invalid_instrument_spec");
                return new Spec(external,type,"BASE_ASSET",base,quote,null,null,null);
            }
            if(!"TRADING".equals(row.path("status").asText())||"swap".equals(type)&&!"PERPETUAL".equals(row.path("contractType").asText()))throw DepthBook.invalid("unsupported_instrument");
            String base=row.path("baseAsset").asText(),quote=row.path("quoteAsset").asText();
            if(base.isEmpty()||quote.isEmpty())throw DepthBook.invalid("invalid_instrument_spec");
            return new Spec(external,type,"BASE_ASSET",base,quote,null,null,null);
        }
        throw DepthBook.invalid("unsupported_instrument");
    }
    DepthBook snapshot(Spec spec){String url=okx()?base(spec.marketType)+"/api/v5/market/books?instId="+spec.externalSymbol+"&sz=20"
        :base(spec.marketType)+("swap".equals(spec.marketType)?"/fapi/v1/depth":"/api/v3/depth")+"?symbol="+spec.externalSymbol+"&limit=20";
        JsonNode root=get(url,65536);if(okx())root=root.path(0);
        return DepthBook.parse(root,okx(),"swap".equals(spec.marketType),false,System.currentTimeMillis());
    }
}
