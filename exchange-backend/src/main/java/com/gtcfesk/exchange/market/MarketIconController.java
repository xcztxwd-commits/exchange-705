package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Bundled, versioned SVGs: icon requests never depend on a third-party network. */
@RestController
public class MarketIconController {
    private static final Map<String,byte[]> ICONS = load();
    private static final Map<String,String> FLAGS = new HashMap<>();
    static {
        for(String pair:("USD:us EUR:eu JPY:jp GBP:gb CHF:ch AUD:au CAD:ca NZD:nz CNY:cn CNH:cn HKD:hk SGD:sg KRW:kr INR:in BRL:br MXN:mx ZAR:za TRY:tr RUB:ru NOK:no SEK:se DKK:dk PLN:pl CZK:cz HUF:hu ILS:il AED:ae SAR:sa THB:th IDR:id MYR:my PHP:ph TWD:tw VND:vn RON:ro ARS:ar CLP:cl COP:co PKR:pk EGP:eg").split(" ")) {
            String[] parts=pair.split(":");FLAGS.put(parts[0],parts[1]);
        }
    }
    private static Map<String,byte[]> load() {
        Map<String,byte[]> result=new HashMap<>();
        try(ZipInputStream zip=new ZipInputStream(MarketIconController.class.getResourceAsStream("/market-icons.zip"))) {
            ZipEntry entry;byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null) {
                ByteArrayOutputStream data=new ByteArrayOutputStream();int size;
                while((size=zip.read(buffer))!=-1)data.write(buffer,0,size);
                result.put(entry.getName(),data.toByteArray());
            }
        } catch(IOException e) { throw new IllegalStateException("Bundled market icons unavailable",e); }
        return Collections.unmodifiableMap(result);
    }
    public static String url(TradingSymbol symbol) {
        String category=symbol.getSourceCategory(),kind="symbol",code=symbol.getSymbol();
        if("Forex".equals(category)){kind="forex";code=symbol.getBaseCurrency()+"-"+symbol.getQuoteCurrency();}
        else if("Crypto".equals(category)||"CryptoPerpetual".equals(category)){kind="crypto";code=symbol.getBaseCurrency();}
        else if("US".equals(category))kind="stocks";
        else if("Metal".equals(category)){kind="metal";code=symbol.getBaseCurrency();}
        else if("Oil".equals(category))kind="oil";
        else if("CFD".equals(category))kind="index";
        code=Objects.toString(code,"?").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]","");
        if(code.isEmpty())code="UNKNOWN";
        return "/market/icons/"+kind+"/"+code+".svg?v=1";
    }
    @GetMapping(value="/api/market/icons/{kind}/{code}.svg",produces="image/svg+xml")
    public ResponseEntity<byte[]> icon(@PathVariable String kind,@PathVariable String code) {
        if(!Arrays.asList("crypto","forex","stocks","metal","oil","index","symbol").contains(kind)||!code.matches("[A-Z0-9._-]{1,40}"))return ResponseEntity.notFound().build();
        String content;
        if("forex".equals(kind)&&code.matches("[A-Z]{3}-[A-Z]{3}")) {
            String[] pair=code.split("-");
            content="<defs><clipPath id='base'><circle cx='23' cy='23' r='22'/></clipPath><clipPath id='quote'><circle cx='43' cy='43' r='20'/></clipPath></defs>"
                +"<g clip-path='url(#base)'>"+flag(pair[0],1,1,44)+"</g><circle cx='43' cy='43' r='22' fill='white'/><g clip-path='url(#quote)'>"+flag(pair[1],23,23,40)+"</g>";
        } else {
            byte[] bytes=ICONS.get(kind+"/"+("crypto".equals(kind)?code.toLowerCase(Locale.ROOT):code)+".svg");
            if(bytes!=null) content="<circle cx='32' cy='32' r='32' fill='white'/>"+embed(bytes,"stocks".equals(kind)?10:0,"stocks".equals(kind)?10:0,"stocks".equals(kind)?44:64);
            else content=badge(code,"metal".equals(kind)?"#b78322":"oil".equals(kind)?"#475569":"index".equals(kind)?"#7c3aed":"#2563eb");
        }
        byte[] body=("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'><title>"+code+"</title>"+content+"</svg>").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.valueOf("image/svg+xml")).cacheControl(CacheControl.maxAge(7,TimeUnit.DAYS).cachePublic())
            .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'").body(body);
    }
    private static String flag(String currency,int x,int y,int size) {
        byte[] bytes=ICONS.get("flags/"+FLAGS.get(currency)+".svg");
        return bytes==null?"<svg x='"+x+"' y='"+y+"' width='"+size+"' height='"+size+"' viewBox='0 0 64 64'>"+badge(currency,"#334155")+"</svg>":embed(bytes,x,y,size);
    }
    private static String embed(byte[] bytes,int x,int y,int size) {
        return new String(bytes,StandardCharsets.UTF_8).replaceFirst("<svg", "<svg x='"+x+"' y='"+y+"' width='"+size+"' height='"+size+"'");
    }
    private static String badge(String code,String color) {
        String text=code.length()>8?code.substring(0,8):code;
        return "<circle cx='32' cy='32' r='32' fill='"+color+"'/><text x='32' y='33' text-anchor='middle' dominant-baseline='middle' fill='white' font-family='Arial,sans-serif' font-weight='700' font-size='"+(text.length()>4?10:17)+"'>"+text+"</text>";
    }
}
