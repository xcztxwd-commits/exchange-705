package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import java.net.*;
import java.math.BigDecimal;
import java.util.*;

/** Provider-owned catalogs, with bounded caching. Added flags always come from the current database. */
@Service
public class MarketInstrumentCatalog {
    @Autowired MarketHttp http;
    @Autowired TradingSymbolRepository symbols;
    @Autowired ExchangeQuoteSource exchange;
    @Value("${market.catalog.yahoo-url:https://query1.finance.yahoo.com}") String yahooUrl="https://query1.finance.yahoo.com";
    private static final int PAGE_SIZE=50;
    private static class Cached { final JsonNode value; final long at=System.currentTimeMillis(); Cached(JsonNode value){this.value=value;} }
    private final Map<String,Cached> cache=new LinkedHashMap<String,Cached>(16,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<String,Cached> entry){return size()>128;}
    };
    public static String inferredSource(String category) {
        return Arrays.asList("Crypto","CryptoPerpetual","Metal").contains(category)?"binance":Arrays.asList("US","Forex","CFD","Oil").contains(category)?"yahoo":null;
    }
    public static String identity(TradingSymbol symbol) {
        String category=ForexQuoteMarketService.sourceCategory(symbol);
        String source=symbol.getMarketSource();

        validate(source,category);
        String external=ForexQuoteMarketService.marketCode(symbol);
        if(external==null || external.trim().isEmpty())throw new BusinessException("请选择有效的源交易对");
        external="yahoo".equals(source)?MarketQuoteSource.mapSymbolToYahoo(external,category):ExchangeQuoteSource.symbol(external,category,false);
        return source+":"+category+":"+external;
    }
    static void validate(String source,String category) {
        if(source==null || !source.equals(inferredSource(category)))throw new BusinessException("行情源与分类不匹配");
    }
    public List<Map<String,Object>> sources() {
        List<Map<String,Object>> result=new ArrayList<>();
        result.add(option("binance","Binance",new String[][]{{"Crypto","加密货币（现货）"},{"CryptoPerpetual","加密货币（永续合约）"},{"Metal","贵金属（永续合约）"}}));
        result.add(option("yahoo","Yahoo",new String[][]{{"US","股票"},{"Forex","外汇"},{"CFD","指数"},{"Oil","能源期货"}}));
        return result;
    }
    private Map<String,Object> option(String value,String label,String[][] categories) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("value",value);result.put("label",label);
        List<Map<String,String>> list=new ArrayList<>();for(String[] item:categories){Map<String,String> row=new HashMap<>();row.put("value",item[0]);row.put("label",item[1]);list.add(row);}
        result.put("categories",list);return result;
    }
    private JsonNode get(String url,boolean fresh) {
        synchronized(cache){Cached saved=cache.get(url);if(!fresh&&saved!=null&&System.currentTimeMillis()-saved.at<300000)return saved.value;}
        try {
            JsonNode value=ExchangeQuoteSource.JSON.readTree(http.get(URI.create(url),12*1024*1024).getBody());
            if(value.has("code") || !value.path("finance").path("error").isMissingNode()&&!value.path("finance").path("error").isNull())
                throw new BusinessException("行情源未返回有效交易对，请稍后重试");
            synchronized(cache){cache.put(url,new Cached(value));}return value;
        }catch(BusinessException e){throw e;}
        catch(Exception e){throw new BusinessException("交易对目录暂时无法连接，请稍后重试；已添加的币种不受影响");}
    }
    static String encode(String value){try{return URLEncoder.encode(value,"UTF-8");}catch(Exception e){throw new IllegalArgumentException(e);}}
    private static String yahooType(String category){return "US".equals(category)?"equity":"Forex".equals(category)?"currency":"CFD".equals(category)?"index":"future";}
    private List<Map<String,Object>> binance(String category) {
        boolean metal="Metal".equals(category), perpetual=ExchangeQuoteSource.perpetual(category);
        JsonNode rows=get(perpetual?exchange.futuresUrl+"/fapi/v1/exchangeInfo":exchange.spotUrl+"/api/v3/exchangeInfo?permissions=SPOT&showPermissionSets=false&symbolStatus=TRADING",false).path("symbols");
        if(!rows.isArray())throw new BusinessException("Binance 未返回交易对名录");
        List<Map<String,Object>> result=new ArrayList<>();
        for(JsonNode row:rows){
            String code=row.path("symbol").asText();
            if(!"TRADING".equals(row.path("status").asText()))continue;
            if(perpetual) {
                if(!"PERPETUAL".equals(row.path("contractType").asText()))continue;
                boolean precious=Arrays.asList("XAU","XAG").contains(row.path("baseAsset").asText());
                if(metal ? !Arrays.asList("XAUUSDT","XAGUSDT").contains(code) : precious || !"COIN".equals(row.path("underlyingType").asText()))continue;
            } else if(!row.path("isSpotTradingAllowed").asBoolean())continue;
            Map<String,Object> item=item(code,row.path("baseAsset").asText()+" / "+row.path("quoteAsset").asText(),perpetual?"Binance Futures":"Binance Spot");
            item.put("baseCurrency",row.path("baseAsset").asText());item.put("quoteCurrency",row.path("quoteAsset").asText());
            item.put("pricePrecision",8);item.put("volumePrecision",8);item.put("minTradeAmount",BigDecimal.ZERO);
            for(JsonNode filter:row.path("filters")){
                if("PRICE_FILTER".equals(filter.path("filterType").asText()))item.put("pricePrecision",precision(filter.path("tickSize").asText()));
                if("LOT_SIZE".equals(filter.path("filterType").asText())){
                    item.put("volumePrecision",precision(filter.path("stepSize").asText()));item.put("minTradeAmount",new BigDecimal(filter.path("minQty").asText("0")));
                }
            }
            if(!code.matches("[A-Z0-9]{2,32}") || row.path("baseAsset").asText().length()>16 || row.path("quoteAsset").asText().length()>16 || internalCode(category,code).length()>32)
                item.put("unavailableReason","当前系统不支持此交易对代码格式");
            result.add(item);
        }
        result.sort(Comparator.comparing(item->String.valueOf(item.get("symbol"))));return result;
    }
    private static String internalCode(String category,String external){return "CryptoPerpetual".equals(category)?external+"_PERP":external;}
    static int precision(String step){try{return Math.max(0,Math.min(8,new BigDecimal(step).stripTrailingZeros().scale()));}catch(Exception e){return 8;}}
    private Map<String,Object> item(String code,String name,String exchangeName){
        Map<String,Object> item=new LinkedHashMap<>();item.put("symbol",code);item.put("name",name);item.put("exchange",exchangeName);return item;
    }
    private JsonNode lookup(String category,String query,int page){
        return get(yahooUrl+"/v1/finance/lookup?query="+encode(query)+"&type="+yahooType(category)+"&start="+(page*PAGE_SIZE)+"&count="+PAGE_SIZE+"&formatted=false&fetchPricingData=false&lang=en-US&region=US",false).path("finance").path("result").path(0);
    }
    private List<Map<String,Object>> yahooRows(JsonNode rows,String category){
        List<Map<String,Object>> result=new ArrayList<>();
        if(!rows.isArray())throw new BusinessException("Yahoo 未返回交易对搜索结果");
        for(JsonNode row:rows){
            if(!yahooType(category).equalsIgnoreCase(row.path("quoteType").asText()))continue;
            String code=row.path("symbol").asText(),name=row.path("shortName").asText(code);
            if("Oil".equals(category)&&!name.toLowerCase(Locale.ROOT).matches(".*(oil|brent|gas|ethanol|petroleum|diesel).*"))continue;
            Map<String,Object> item=item(code,name,row.path("exchange").asText());
            if(code.length()>32 || !code.matches("[A-Za-z0-9^=._-]+"))item.put("unavailableReason","当前系统不支持此交易对代码格式");
            result.add(item);
        }return result;
    }
    public Map<String,Object> list(String source,String category,String query,int page){
        validate(source,category);if(page<0||page>199||query==null||query.length()>80)throw new BusinessException("查询参数超出范围");
        query=query.trim();List<Map<String,Object>> rows;boolean more;Integer total=null;
        if("binance".equals(source)){
            rows=binance(category);String search=query.toUpperCase(Locale.ROOT);rows.removeIf(row->!(row.get("symbol")+" "+row.get("name")).toUpperCase(Locale.ROOT).contains(search));
            total=rows.size();int from=Math.min(rows.size(),page*PAGE_SIZE),to=Math.min(rows.size(),from+PAGE_SIZE);more=to<rows.size();rows=new ArrayList<>(rows.subList(from,to));
        }else if(query.isEmpty()){rows=new ArrayList<>();more=false;}
        else{JsonNode result=lookup(category,query,page);JsonNode documents=result.path("documents");rows=yahooRows(documents,category);
            more=documents.size()==PAGE_SIZE && page<199 && (page+1)*PAGE_SIZE<result.path("lookupTotals").path(yahooType(category)).asInt(10000);}
        Set<String> existing=new HashSet<>(),names=new HashSet<>();
        for(TradingSymbol symbol:symbols.findAll()){names.add(symbol.getSymbol());try{existing.add(identity(symbol));}catch(RuntimeException ignored){}}
        for(Map<String,Object> row:rows){String code=(String)row.get("symbol");row.put("added",existing.contains(source+":"+category+":"+code));
            TradingSymbol preview=new TradingSymbol();preview.setSymbol(code);preview.setSourceCategory(category);preview.setBaseCurrency(Objects.toString(row.get("baseCurrency"),code));preview.setQuoteCurrency(Objects.toString(row.get("quoteCurrency"),"USD"));
            if("Forex".equals(category)){String[] pair=Objects.toString(row.get("name"),"").split("/");if(pair.length==2){preview.setBaseCurrency(pair[0].trim());preview.setQuoteCurrency(pair[1].trim());}}
            row.put("iconUrl",MarketIconController.url(preview));
            if(!Boolean.TRUE.equals(row.get("added"))&&names.contains(internalCode(category,code)))row.put("unavailableReason","内部交易对代码已被其他品种使用");}
        Map<String,Object> response=new LinkedHashMap<>();response.put("list",rows);response.put("page",page);response.put("pageSize",PAGE_SIZE);response.put("hasMore",more);response.put("total",total);
        response.put("notice","yahoo".equals(source)?"Yahoo 按关键词分页查询；搜索结果不是全市场完整名录。可修改关键词查找其他交易对。":"显示 Binance 当前可交易名录，支持搜索和翻页。");
        return response;
    }
    /** Resolve again on the server; clients cannot supply an arbitrary symbol, source or metadata. */
    public TradingSymbol resolve(String source,String category,String external){
        validate(source,category);if(external==null||external.length()>32||external.isEmpty())throw new BusinessException("无效交易对");
        List<Map<String,Object>> candidates="binance".equals(source)?binance(category):yahooRows(lookup(category,external,0).path("documents"),category);
        Map<String,Object> found=candidates.stream().filter(row->external.equals(row.get("symbol"))).findFirst().orElseThrow(()->new BusinessException("该交易对不在源目录中，请刷新后重试"));
        if(found.containsKey("unavailableReason"))throw new BusinessException((String)found.get("unavailableReason"));
        TradingSymbol symbol=new TradingSymbol();symbol.setSymbol(internalCode(category,external));symbol.setAlltickSymbol(external);symbol.setCategory(category);symbol.setSourceCategory(category);symbol.setMarketSource(source);
        symbol.setName(truncate(String.valueOf(found.get("name"))+("CryptoPerpetual".equals(category)?" 永续":""),64));symbol.setNameEn(symbol.getName());
        if("binance".equals(source)){
            if(!"binance".equals(exchange.provider))throw new BusinessException("当前运行行情源未启用 Binance，暂不能添加 Binance 品种");
            symbol.setBaseCurrency((String)found.get("baseCurrency"));symbol.setQuoteCurrency((String)found.get("quoteCurrency"));
            symbol.setPricePrecision((Integer)found.get("pricePrecision"));symbol.setVolumePrecision((Integer)found.get("volumePrecision"));symbol.setMinTradeAmount((BigDecimal)found.get("minTradeAmount"));
        }else{
            JsonNode meta=get(yahooUrl+"/v8/finance/chart/"+encode(external)+"?range=1d&interval=1d",false).path("chart").path("result").path(0).path("meta");
            String currency=meta.path("currency").asText();
            if(!external.equals(meta.path("symbol").asText())||currency.isEmpty()||currency.length()>16)throw new BusinessException("无法确认交易对的计价货币，请稍后重试");
            String base=external.replace("=X","").replace("=F","").replace("^","");
            if("Forex".equals(category)){String[] pair=String.valueOf(found.get("name")).split("/");if(pair.length==2)base=pair[0];}
            symbol.setBaseCurrency(truncate(base,16));symbol.setQuoteCurrency(currency);symbol.setPricePrecision("Forex".equals(category)?5:meta.path("priceHint").asInt(2));
        }
        symbol.setIconUrl(MarketIconController.url(symbol));
        symbol.setMarketInstrumentKey(identity(symbol));return symbol;
    }
    private static String truncate(String value,int size){return value.length()>size?value.substring(0,size):value;}
}
