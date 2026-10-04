package com.gtcfesk.exchange.insights.news;
import java.util.*;
import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;
public final class NewsCatalog {
    private NewsCatalog(){}
    public static final Map<String,String> URLS,NAMES,LICENSES;
    static {Map<String,String> u=new LinkedHashMap<>(),n=new LinkedHashMap<>(),l=new LinkedHashMap<>();
        u.put("FED","https://www.federalreserve.gov/feeds/press_monetary.xml");n.put("FED","Federal Reserve Board");l.put("FED","https://www.federalreserve.gov/disclaimer.htm");
        u.put("BEA","https://apps.bea.gov/rss/rss.xml");n.put("BEA","U.S. Bureau of Economic Analysis");l.put("BEA","https://www.bea.gov/help/faq/147");
        u.put("ECB","https://www.ecb.europa.eu/rss/press.html");n.put("ECB","European Central Bank");l.put("ECB","https://www.ecb.europa.eu/services/using-our-site/disclaimer/html/index.en.html");
        u.put("GDELT","https://api.gdeltproject.org/api/v2/doc/doc?query=bitcoin&mode=artlist&format=json&maxrecords=10&timespan=24h&sort=datedesc");n.put("GDELT","GDELT headline index");l.put("GDELT","https://gdeltproject.org/data.html");
        u.put("NEWSDATA","https://newsdata.io/api/1/latest?q=bitcoin&language=en");n.put("NEWSDATA","NewsData.io");l.put("NEWSDATA","https://newsdata.io/terms");
        URLS=Collections.unmodifiableMap(u);NAMES=Collections.unmodifiableMap(n);LICENSES=Collections.unmodifiableMap(l);
    }
    public static boolean official(String id){return Arrays.asList("FED","BEA","ECB").contains(id);}
    public static String check(String id){if(!URLS.containsKey(id))throw bad("未知新闻来源");return id;}
    public static int interval(String id){return id.equals("GDELT")?21600:id.equals("NEWSDATA")?43200:3600;}
    public static int budget(String id){return id.equals("GDELT")?4:id.equals("NEWSDATA")?2:24;}
    public static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
