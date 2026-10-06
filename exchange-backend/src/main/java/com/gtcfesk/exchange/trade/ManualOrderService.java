package com.gtcfesk.exchange.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.user.ManualOrderHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service @RequiredArgsConstructor
public class ManualOrderService {
    private static final String SIMPLE_CONFIG="row_version,max_leverage,category,lot_size,fee_multiplier,quote_currency,market_source,base_currency,source_category,quantity_unit_type,spec_version,min_order_quantity,quantity_step,min_order_notional";
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final TradingSymbolRepository symbols;
    private final ManualOrderPrices prices;
    private final YahooHistoryCalendar yahooCalendar;
    private final ManualOrderHistory history;
    private final com.gtcfesk.exchange.market.MarketCategoryService categories;
    @Value("${manual.orders.enabled:false}") private boolean enabled;
    private final Map<String,Preview> previews=new ConcurrentHashMap<>();
    private final Map<String,BindingPreview> bindingPreviews=new ConcurrentHashMap<>();
    public static class Request {
        public Long userId;
        public Long specVersion;
        public String quantityUnitType;
        public String symbol,side,timezone,openLocal,closeLocal,openOffset,closeOffset,driver;
        public BigDecimal input,leverage,targetNet;
        public BigDecimal openPrice,closePrice,netTolerance;
        public boolean simpleMode,allowNetAdjustment=true;
        public boolean walletEnabled,historyEnabled;
        public String previewToken,idempotencyKey;
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String key,Object value) {throw new BusinessException("不支持的手动订单参数: "+key);}
    }
    private static class Preview {
        long operator,expires,open,close,accountVersion,symbolVersion,symbolId;
        Long tenantId; String hash,currency,source,fxBase;BigDecimal available,lot,fee;
        TradingSymbol specification;
        Map<String,Object> quotes,symbolConfiguration;Map<String,BigDecimal> calculation;
    }
    private long authorize() {
        Authentication a=SecurityContextHolder.getContext().getAuthentication();
        if(a==null || a.getAuthorities().stream().noneMatch(r->r.getAuthority().equals("ROLE_SUPER_ADMIN"))) throw new AccessDeniedException("仅限内测超级管理员");
        com.gtcfesk.exchange.control.ControlIdentity control=com.gtcfesk.exchange.control.ControlIdentity.current();
        if(control!=null && (!com.gtcfesk.exchange.tenant.TenantContext.requireTenantId().equals(control.getTenantId()) || !Long.toString(-control.getActorId()).equals(a.getName()) || control.getAccessSessionId()==null))throw new AccessDeniedException("总控访问身份不匹配");
        if(!enabled) throw new BusinessException("手动订单内测开关未开启");
        return Long.parseLong(a.getName());
    }
    public Map<String,Object> context(Long user,String search) {
        authorize();JdbcTemplate db=new JdbcTemplate(dataSource);Map<String,Object> out=new LinkedHashMap<>();
        String q=search==null?"":search.trim();
        out.put("users",db.queryForList("select id,email from user_account where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and (cast(id as char)=? or email like ?) order by id desc limit 30",q,"%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"));
        out.put("symbols",db.queryForList("select symbol,name,category,source_category,max_leverage,lot_size,fee_multiplier,quantity_unit_type,spec_version,min_order_quantity,quantity_step,min_order_notional,base_currency from trading_symbol where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and is_enabled=1 order by sort_order,id"));
        List<String> zones=db.query("select config_value from system_config where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and config_key='system.timezone'",(r,n)->r.getString(1));
        out.put("timezone",zones.isEmpty()?"Europe/London":zones.get(0));out.put("maxHistoryRows",ManualOrderHistory.MAX_ROWS);
        if(user!=null) {out.put("user",db.queryForMap("select id,email from user_account where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and id=?",user));out.put("account",account(db,user,false));}
        return out;
    }
    public Map<String,Object> minutes(String symbol,String date,String timezone) {
        authorize();
        TradingSymbol s=symbols.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), symbol).orElseThrow(()->new BusinessException("品种不存在"));
        if(!Boolean.TRUE.equals(s.getIsEnabled()))throw new BusinessException("品种已停用");
        return prices.minutes(s,date,timezone);
    }
    public Map<String,Object> chart(String symbol,String timezone) {return chart(symbol,timezone,null,null);}
    public Map<String,Object> chart(String symbol,String timezone,Long endTime,Integer limit) {
        authorize();
        TradingSymbol s=symbols.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),symbol).orElseThrow(()->new BusinessException("品种不存在"));
        if(!Boolean.TRUE.equals(s.getIsEnabled()))throw new BusinessException("品种已停用");
        return endTime==null && limit==null?prices.chart(s,timezone):prices.chart(s,timezone,endTime,limit==null?200:limit);
    }
    public Map<String,Object> calendar(String symbol,String month,String timezone,int page) {
        authorize();
        TradingSymbol s=symbols.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), symbol).orElseThrow(()->new BusinessException("品种不存在"));
        if(!Boolean.TRUE.equals(s.getIsEnabled()))throw new BusinessException("品种已停用");
        return yahooCalendar.page(s,month,timezone,page);
    }
    private Map<String,Object> account(JdbcTemplate db,long user,boolean lock) {
        List<Map<String,Object>> rows=db.queryForList("select id,available,frozen,row_version from asset_account where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and coin='CONTRACT'"+(lock?" for update":""),user);
        if(rows.isEmpty()) throw new BusinessException("用户尚未建立合约钱包，请先初始化测试账户");
        return rows.get(0);
    }
    public Map<String,Object> preview(Request r) {
        long operator=authorize(),now=System.currentTimeMillis();validate(r,now);
        long open=ManualOrderCalculation.minute(r.openLocal,r.timezone,r.openOffset),close=ManualOrderCalculation.minute(r.closeLocal,r.timezone,r.closeOffset);
        TradingSymbol s=symbols.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), r.symbol).orElseThrow(()->new BusinessException("品种不存在"));
        QuantityRules.protocol(s,r.specVersion,r.quantityUnitType);
        if(!Boolean.TRUE.equals(s.getIsEnabled()))throw new BusinessException("品种已停用");
        Preview p=new Preview(),old=r.previewToken==null?null:previews.get(r.previewToken);
        p.quotes=old!=null && com.gtcfesk.exchange.tenant.TenantContext.requireTenantId().equals(old.tenantId) && old.operator==operator && old.expires>now && old.symbolId==s.getId() && old.symbolVersion==s.getRowVersion() && old.open==open && old.close==close
                && (r.simpleMode ? "SIMPLE_OHLC_RANGE".equals(old.quotes.get("priceBasis")) && r.openPrice.compareTo(new BigDecimal(old.quotes.get("openPrice").toString()))==0 && r.closePrice.compareTo(new BigDecimal(old.quotes.get("closePrice").toString()))==0 : "EXACT_MINUTE_OPEN".equals(old.quotes.get("priceBasis")))
                ?old.quotes:r.simpleMode?prices.rangeQuote(s,open,close,r.openPrice,r.closePrice):prices.quote(s,open,close);
        JdbcTemplate db=new JdbcTemplate(dataSource);Map<String,Object> account=r.userId==null?emptyAccount():account(db,r.userId,false);
        p.tenantId=com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();p.operator=operator;p.open=open;p.close=close;p.expires=now+300000;p.hash=hash(r,operator);
        p.accountVersion=((Number)account.get("row_version")).longValue();p.available=(BigDecimal)account.get("available");
        p.symbolId=s.getId();p.symbolVersion=s.getRowVersion();p.lot=s.getLotSize()==null?new BigDecimal("1000"):s.getLotSize();p.fee=s.getFeeMultiplier()==null?new BigDecimal("30"):s.getFeeMultiplier();
        p.specification=s;
        if(r.simpleMode)p.symbolConfiguration=db.queryForMap("select "+SIMPLE_CONFIG+" from trading_symbol where tenant_id=? and id=?",p.tenantId,s.getId());
        p.currency=s.getQuoteCurrency();p.source=s.getMarketSource();p.fxBase=FxContractRules.isForex(s)?s.getBaseCurrency():null;p.calculation=calculate(r,p);
        Map<String,Object> out=evidence(r,p);out.put("history",r.historyEnabled?history.inspect(db,r.userId,close):null);
        previews.entrySet().removeIf(e->e.getValue().expires<=now);
        // ponytail: bounded in-process previews; restart requires preview again, committed retries are durable.
        if(previews.size()>=1000)throw new BusinessException("预览繁忙，请稍后重试");
        String token=UUID.randomUUID().toString();previews.put(token,p);out.put("previewToken",token);return out;
    }
    public Map<String,Object> generate(ManualOrderGenerator.Request r) {
        authorize();
        if(r==null || r.userId==null || r.userId<=0 || r.symbol==null)throw new BusinessException("请先选择用户与品种");
        if(r.historyEnabled && !r.walletEnabled)throw new BusinessException("历史开启必须同时开启钱包入账");
        ZoneId zone;
        try {zone=ZoneId.of(r.timezone);}catch(DateTimeException | NullPointerException e){throw new BusinessException("请选择有效时区");}
        long now=System.currentTimeMillis(),end=Math.floorDiv(now,60000)*60000;
        Long open=r.openLocal==null?null:ManualOrderCalculation.minute(r.openLocal,r.timezone,r.openOffset);
        Long close=r.closeLocal==null?null:ManualOrderCalculation.minute(r.closeLocal,r.timezone,r.closeOffset);
        if(open!=null && (open<0 || open>=end) || close!=null && (close<0 || close>=end) || open!=null && close!=null && open>close)
            throw new BusinessException("生成需使用已结束分钟，且开仓不能晚于平仓");
        TradingSymbol s=symbols.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), r.symbol).orElseThrow(()->new BusinessException("品种不存在"));
        if(!Boolean.TRUE.equals(s.getIsEnabled()))throw new BusinessException("品种已停用");
        BigDecimal available=(BigDecimal)account(new JdbcTemplate(dataSource),r.userId,false).get("available");
        QuantityRules.protocol(s,r.specVersion,r.quantityUnitType);
        ManualOrderGenerator.validate(r,available);
        BigDecimal lot=s.getLotSize()==null?new BigDecimal("1000"):s.getLotSize(),fee=s.getFeeMultiplier()==null?new BigDecimal("30"):s.getFeeMultiplier();
        boolean autoClose=close==null;
        long from,to;
        NavigableMap<Long,ManualOrderGenerator.Candle> candles;
        if(autoClose) {
            long recentFrom=Math.max(0,end-ManualOrderGenerator.RANGE);
            NavigableMap<Long,ManualOrderGenerator.Candle> recent=prices.generationCandles(s,recentFrom,end);
            if(recent.isEmpty())throw new BusinessException("最近 7 天没有可用的平仓分钟行情；请更换品种或稍后重试");
            close=recent.lastKey();
            if(open!=null && (open>close || open<close-ManualOrderGenerator.RANGE))
                throw new BusinessException("填写的开仓时间不在最近有效平仓分钟之前的 7 天内");
            from=open!=null?open:Math.max(0,close-ManualOrderGenerator.RANGE);to=close+60000;
            candles=new TreeMap<>(recent.subMap(Math.max(from,recentFrom),true,to,false));
            if(from<recentFrom)candles.putAll(prices.generationCandles(s,from,recentFrom));
        } else if(open!=null) {
            from=open;to=close+60000;
            Map<String,Object> q=prices.quote(s,open,close);candles=new TreeMap<>();
            candles.put(open,new ManualOrderGenerator.Candle(open,new BigDecimal(q.get("openPrice").toString()),new BigDecimal(q.get("openRate").toString()),new BigDecimal(q.get("marginRate").toString())));
            candles.put(close,new ManualOrderGenerator.Candle(close,new BigDecimal(q.get("closePrice").toString()),new BigDecimal(q.get("closeRate").toString()),open.equals(close)?new BigDecimal(q.get("marginRate").toString()):new BigDecimal(q.get("closePrice").toString()).multiply(new BigDecimal(q.get("closeRate").toString()))));
        } else {
            from=Math.max(0,close-ManualOrderGenerator.RANGE);to=close+60000;
            candles=prices.generationCandles(s,from,to);
        }
        ManualOrderGenerator.Candle ending=candles.get(close);
        if(r.targetClosePrice!=null && ending!=null && !ManualOrderGenerator.withinTarget(ending.price,r.targetClosePrice))
            throw new BusinessException((autoClose?"最近有效":"所选")+"平仓分钟 "+Instant.ofEpochMilli(close).atZone(zone)+" 的价格 "+ending.price.toPlainString()+" 不在目标平仓价 "+r.targetClosePrice.toPlainString()+" 的 ±5% 范围；不修改历史价格");
        ManualOrderGenerator.Candidate best;
        try {best=ManualOrderGenerator.solve(r,candles,open,close,available,lot,fee,UUID.randomUUID().getLeastSignificantBits(),s.getQuantityStep()==null?new BigDecimal("0.01"):s.getQuantityStep(),s.getMinOrderQuantity()==null?new BigDecimal("0.01"):s.getMinOrderQuantity(),s.getMinOrderNotional()==null?BigDecimal.ZERO:s.getMinOrderNotional(),maxLeverage(s.getMaxLeverage(),s.getCategory()));}
        catch(BusinessException e) {if(autoClose)throw new BusinessException("最近有效平仓分钟 "+Instant.ofEpochMilli(close).atZone(zone)+" 之前 7 天没有符合条件的开仓分钟："+e.getMessage());throw e;}
        Request generated=new Request();generated.specVersion=r.specVersion;generated.quantityUnitType=r.quantityUnitType;generated.userId=r.userId;generated.symbol=r.symbol;generated.timezone=r.timezone;generated.side=best.side;generated.leverage=best.leverage;
        ZonedDateTime a=Instant.ofEpochMilli(best.open.time).atZone(zone),b=Instant.ofEpochMilli(best.close.time).atZone(zone);
        generated.openLocal=a.toLocalDateTime().toString();generated.closeLocal=b.toLocalDateTime().toString();generated.openOffset=a.getOffset().toString();generated.closeOffset=b.getOffset().toString();
        generated.driver="QUANTITY";generated.input=best.calculation.get("quantity");generated.targetNet=r.targetNet;generated.walletEnabled=r.walletEnabled;generated.historyEnabled=r.historyEnabled;
        Map<String,Object> out=preview(generated);
        @SuppressWarnings("unchecked") Map<String,BigDecimal> actual=(Map<String,BigDecimal>)out.get("calculation");
        BigDecimal actualClosePrice=new BigDecimal(((Map<?,?>)out.get("quotes")).get("closePrice").toString());
        if(!ManualOrderGenerator.withinTarget(actual.get("net"),r.targetNet) || !ManualOrderGenerator.withinTarget(actualClosePrice,r.targetClosePrice) || !ManualOrderGenerator.matches(r,actual,generated.leverage)) {
            previews.remove(out.get("previewToken").toString());throw new BusinessException("行情或余额已变化，最终预览不满足固定条件或数值目标 ±5%，请重新生成");
        }
        Map<String,Object> info=new LinkedHashMap<>();info.put("targetNet",r.targetNet);info.put("difference",r.targetNet==null?null:actual.get("net").subtract(r.targetNet));
        info.put("errorPercent",ManualOrderGenerator.error(actual.get("net"),r.targetNet));info.put("durationMinutes",(best.close.time-best.open.time)/60000);
        info.put("leverageTarget",r.leverage);info.put("leverageErrorPercent",ManualOrderGenerator.error(generated.leverage,r.leverage));
        info.put("quantityTarget",r.quantity);info.put("quantityErrorPercent",ManualOrderGenerator.error(actual.get("quantity"),r.quantity));
        info.put("percentTarget",r.percent);info.put("percentErrorPercent",r.percent==null?BigDecimal.ZERO:ManualOrderGenerator.error(actual.get("percent"),r.percent));
        info.put("closePriceTarget",r.targetClosePrice);info.put("closePriceErrorPercent",ManualOrderGenerator.error(actualClosePrice,r.targetClosePrice));info.put("closeAutomaticallySelected",autoClose);
        info.put("searchedPairs",best.pairs);info.put("from",Instant.ofEpochMilli(from).toString());info.put("to",Instant.ofEpochMilli(to).toString());
        String warning=best.close.time-best.open.time<30*60000L?"可行条件限制，当前生成持仓较短；建议取消部分固定条件或换时间":"优先选择小时级持仓、适中仓位与杠杆；模拟订单仍带手动标记";
        if(actual.get("percent")!=null && actual.get("percent").compareTo(new BigDecimal("100"))>0)warning+="；当前结果超过 100% 仓位，不代表实际资金可承受的交易";
        info.put("warning",warning);
        out.put("generation",info);return out;
    }
    private void validate(Request r,long now) {
        if(r==null || (!r.simpleMode && r.userId==null) || r.userId!=null && r.userId<=0 || r.symbol==null || r.symbol.length()>32)throw new BusinessException("用户或品种无效");
        if(r.simpleMode) {
            if(r.userId==null && (r.walletEnabled || r.historyEnabled))throw new BusinessException("未绑定订单不能入钱包或回填历史权益");
            if(!"QUANTITY".equals(r.driver))throw new BusinessException("简版必须使用确认后的数量");
            ManualOrderCalculation.positive(r.openPrice,"开仓价格");ManualOrderCalculation.positive(r.closePrice,"平仓价格");SimpleManualOrderGenerator.tolerance(r.netTolerance);
        } else if(r.openPrice!=null || r.closePrice!=null || r.netTolerance!=null)throw new BusinessException("价格与容差参数仅用于简版模拟订单");
        if(r.historyEnabled && !r.walletEnabled)throw new BusinessException("历史开启必须同时开启钱包入账");
        long open=ManualOrderCalculation.minute(r.openLocal,r.timezone,r.openOffset),close=ManualOrderCalculation.minute(r.closeLocal,r.timezone,r.closeOffset);
        if(open>close || close>now || open<0 || r.simpleMode && (open>=close || close+60000>now))throw new BusinessException("开平仓时间顺序无效或使用未来时间");
        if(r.historyEnabled && close+60000>now)throw new BusinessException("历史开启时请等待平仓分钟结束");
    }
    private BigDecimal decimal(Preview p,String name) {return new BigDecimal(p.quotes.get(name).toString());}
    private Map<String,BigDecimal> calculate(Request r,Preview p) {
        ManualOrderCalculation.leverage(r.leverage,maxLeverage(p.specification.getMaxLeverage(),p.specification.getCategory()));
        Map<String,BigDecimal> calculation=ManualOrderCalculation.calculate(r.driver,r.input,r.side,p.available,decimal(p,"openPrice"),decimal(p,"closePrice"),p.lot,r.leverage,decimal(p,"openRate"),decimal(p,"closeRate"),p.fee,p.quotes.get("marginRate")==null?decimal(p,"openPrice").multiply(decimal(p,"openRate")):decimal(p,"marginRate"),p.specification.getQuantityStep()==null?new BigDecimal("0.01"):p.specification.getQuantityStep());
        QuantityRules.quantity(p.specification,calculation.get("quantity"));
        QuantityRules.notional(calculation.get("quantity"),p.lot,decimal(p,"openPrice"),decimal(p,"openRate"),p.specification.getMinOrderNotional());
        BigDecimal target=r.targetNet!=null?r.targetNet:"NET".equals(r.driver)?r.input:null;
        if(target!=null) {
            ManualOrderCalculation.number(target,32,16,"目标净收益");
            if(!(r.simpleMode?SimpleManualOrderGenerator.matches(calculation.get("net"),target,r.allowNetAdjustment,r.netTolerance):ManualOrderGenerator.withinTarget(calculation.get("net"),target)))throw new BusinessException("当前条件下实际净收益 "+calculation.get("net").stripTrailingZeros().toPlainString()+(r.simpleMode?" 不满足设置的净收益容差或严格匹配要求":" 不满足目标 ±5%（零目标须精确匹配）；请一键生成或调整固定条件"));
            calculation.put("difference",ManualOrderCalculation.money(calculation.get("net").subtract(target)));
        }
        return calculation;
    }
    private Map<String,Object> evidence(Request r,Preview p) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("request",json.convertValue(r,Map.class));out.put("quotes",p.quotes);out.put("calculation",p.calculation);
        out.put("walletBefore",p.available);out.put("walletAfter",ManualOrderCalculation.money(p.available.add(r.walletEnabled?p.calculation.get("net"):BigDecimal.ZERO)));
        if(r.simpleMode) {
            @SuppressWarnings("unchecked") Map<String,Object> wire=(Map<String,Object>)out.get("request");
            wire.put("input",canonicalDecimal(r.input));wire.put("leverage",r.leverage==null?null:r.leverage.toPlainString());wire.put("targetNet",canonicalDecimal(r.targetNet));wire.put("openPrice",canonicalDecimal(r.openPrice));wire.put("closePrice",canonicalDecimal(r.closePrice));wire.put("netTolerance",canonicalDecimal(r.netTolerance));
            if(r.userId==null) {out.put("walletBefore",null);out.put("walletAfter",null);}
        }
        out.put("accountVersion",p.accountVersion);out.put("symbolVersion",p.symbolVersion);out.put("lotSize",p.lot);out.put("feePerLot",p.fee);out.put("expiresAt",p.expires);
        out.put("quantityUnitType",p.specification.getQuantityUnitType());out.put("quantityAsset",p.specification.getBaseCurrency());out.put("specVersion",p.specification.getSpecVersion());out.put("quantityStep",p.specification.getQuantityStep());out.put("minOrderQuantity",p.specification.getMinOrderQuantity());out.put("minOrderNotional",p.specification.getMinOrderNotional());
        out.put("openUtc",Instant.ofEpochMilli(p.open).toString());out.put("closeUtc",Instant.ofEpochMilli(p.close).toString());
        out.put("openOffset",Instant.ofEpochMilli(p.open).atZone(ZoneId.of(r.timezone)).getOffset().toString());out.put("closeOffset",Instant.ofEpochMilli(p.close).atZone(ZoneId.of(r.timezone)).getOffset().toString());
        return r.simpleMode?decimalStrings(out):out;
    }
    // Keep decimal values as strings on the simple UI wire; never narrow ledger precision to JS Number.
    private Map<String,Object> decimalStrings(Map<?,?> source) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(Map.Entry<?,?> e:source.entrySet()) {
            Object value=e.getValue();
            out.put(e.getKey().toString(),value instanceof BigDecimal?((BigDecimal)value).toPlainString():value instanceof Map?decimalStrings((Map<?,?>)value):value);
        }
        return out;
    }
    private String hash(Request r,long operator) {
        Map<String,Object> fields=new TreeMap<>(json.convertValue(r,Map.class));fields.remove("previewToken");fields.remove("idempotencyKey");if(r.targetNet==null)fields.remove("targetNet");fields.put("operator",operator);fields.put("tenantId",com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        // Use original decimals: convertValue to an untyped Map may first narrow them to Double.
        fields.put("input",canonicalDecimal(r.input));fields.put("leverage",canonicalDecimal(r.leverage));
        if(r.targetNet!=null)fields.put("targetNet",canonicalDecimal(r.targetNet));
        if(r.simpleMode) {fields.put("openPrice",canonicalDecimal(r.openPrice));fields.put("closePrice",canonicalDecimal(r.closePrice));fields.put("netTolerance",canonicalDecimal(r.netTolerance));}
        return digest(encode(fields));
    }
    private String canonicalDecimal(BigDecimal value) {return value==null?null:value.stripTrailingZeros().toString();}
    private boolean savedRequestMatches(Map<String,Object> saved,String expected) {
        if(expected.equals(saved.get("request_hash")))return true;
        // Pre-normalization audit rows remain replayable without weakening numeric or operator binding.
        try {
            com.fasterxml.jackson.databind.JsonNode evidence=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(saved.get("evidence").toString());
            Request original=json.treeToValue(evidence.get("request"),Request.class);
            return expected.equals(hash(original,((Number)saved.get("operator_id")).longValue()));
        } catch(Exception invalidEvidence) {return false;}
    }
    private String digest(String value) {
        try {StringBuilder b=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))b.append(String.format("%02x",v));return b.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    // Test seam only, never a request parameter or production failure flag.
    protected void checkpoint(String stage) { }
    public Map<String,Object> create(Request r) {
        long operator=authorize();
        if(r==null || r.idempotencyKey==null || !r.idempotencyKey.matches("[A-Za-z0-9_-]{16,64}"))throw new BusinessException("幂等键无效");
        String hash=hash(r,operator);
        // All writes use one physical JDBC connection, including row versions and durable audit.
        // No outer JPA transaction, no periodic job's multi-commit Session is involved.
        try(Connection c=dataSource.getConnection()) {
            boolean auto=c.getAutoCommit();int isolation=c.getTransactionIsolation();List<String> locks=new ArrayList<>();
            JdbcTemplate db=new JdbcTemplate(new SingleConnectionDataSource(c,true));db.setQueryTimeout(15);
            try {
                lock(db,locks,"manual_"+digest(r.idempotencyKey).substring(0,24));
                // Durable replay must precede preview expiry and account-version checks.
                List<Map<String,Object>> saved=db.queryForList("select request_hash,order_id,operator_id,evidence from manual_order_record where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and idempotency_key=?",r.idempotencyKey);
                if(!saved.isEmpty()) {
                    if(!savedRequestMatches(saved.get(0),hash))throw new BusinessException("同一幂等键不能提交不同内容");
                    return Collections.singletonMap("orderId",saved.get(0).get("order_id"));
                }
                validate(r,System.currentTimeMillis());Preview p=previews.get(r.previewToken==null?"":r.previewToken);
                if(p==null || p.expires<System.currentTimeMillis() || !com.gtcfesk.exchange.tenant.TenantContext.requireTenantId().equals(p.tenantId) || p.operator!=operator || !p.hash.equals(hash))throw new BusinessException("预览过期或参数已变更，请重新预览");
                if(r.historyEnabled) {lock(db,locks,"equity_v1_capture");lock(db,locks,"equity_v1_rollup");}
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
                if(r.userId!=null && db.queryForList("select id from user_account where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and id=? for update",r.userId).isEmpty())throw new BusinessException("用户不存在");
                Map<String,Object> account=r.userId==null?emptyAccount():account(db,r.userId,true);
                if(((Number)account.get("row_version")).longValue()!=p.accountVersion || ((BigDecimal)account.get("available")).compareTo(p.available)!=0)throw new BusinessException("钱包已变化，请重新预览");
                List<Map<String,Object>> configs=db.queryForList("select "+(r.simpleMode?SIMPLE_CONFIG:"row_version,max_leverage,category")+" from trading_symbol where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and id=? and is_enabled=1 lock in share mode",p.symbolId);
                if(configs.isEmpty() || ((Number)configs.get(0).get("row_version")).longValue()!=p.symbolVersion || r.simpleMode && !configs.get(0).equals(p.symbolConfiguration))throw new BusinessException("品种配置已变化，请重新预览");
                ManualOrderCalculation.leverage(r.leverage,maxLeverage((BigDecimal)configs.get(0).get("max_leverage"),(String)configs.get(0).get("category")));
                p.calculation=calculate(r,p);Map<String,BigDecimal> v=p.calculation;long now=System.currentTimeMillis();
                db.update("insert into contract_order(tenant_id,user_id,symbol,side,type,status,order_source,manual_wallet_enabled,manual_equity_enabled,quantity,leverage,lot_size,margin,fee,profit,open_price,close_price,current_price,open_time,close_time,created_at,updated_at,row_version,limit_match_enabled,quote_currency,quote_source,margin_conversion_rate,settlement_conversion_rate,fx_base_currency) values("+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+",?,?,?,'MARKET','CLOSED','MANUAL_TEST',?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0,0,?,?,?,?,?)",
                        r.userId,r.symbol,r.side,r.walletEnabled,r.historyEnabled,v.get("quantity"),r.leverage,p.lot,v.get("margin"),v.get("fee"),v.get("profit"),decimal(p,"openPrice"),decimal(p,"closePrice"),decimal(p,"closePrice"),LocalDateTime.ofInstant(Instant.ofEpochMilli(p.open),ZoneOffset.UTC).toString(),LocalDateTime.ofInstant(Instant.ofEpochMilli(p.close),ZoneOffset.UTC).toString(),p.currency,p.source,p.fxBase==null?decimal(p,"openRate"):decimal(p,"marginRate"),decimal(p,"closeRate"),p.fxBase);
                Long order=db.queryForObject("select last_insert_id()",Long.class);
                if(QuantityRules.configured(p.specification)) db.update("update contract_order set quantity_unit_type=?,quantity_asset=?,spec_version=?,min_order_quantity=?,quantity_step=?,min_order_notional=? where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and id=?",
                    p.specification.getQuantityUnitType(),p.specification.getBaseCurrency(),p.specification.getSpecVersion(),p.specification.getMinOrderQuantity(),p.specification.getQuantityStep(),p.specification.getMinOrderNotional(),order);
                checkpoint("order");
                if(r.walletEnabled) {
                    if(ManualOrderCalculation.money(p.available.add(v.get("net"))).signum()<0)throw new BusinessException("手动订单入账后钱包余额不能为负");
                    if(db.update("update asset_account set available=available+?,row_version=row_version+1,updated_at=UTC_TIMESTAMP() where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and id=? and row_version=?",v.get("net"),account.get("id"),p.accountVersion)!=1)throw new BusinessException("钱包版本冲突，请重新预览");
                }
                checkpoint("wallet");Map<String,Object> evidence=evidence(r,p);
                com.gtcfesk.exchange.control.ControlIdentity actor=com.gtcfesk.exchange.control.ControlIdentity.current();
                evidence.put("actorType",actor==null?"ADMIN":"CONTROL");evidence.put("actorId",actor==null?operator:actor.getActorId());evidence.put("accessSessionId",actor==null?null:actor.getAccessSessionId());
                evidence.put("history",r.historyEnabled?history.apply(db,r.userId,p.close,v.get("net"),now,this::checkpoint):null);
                db.update("insert into manual_order_record(tenant_id,idempotency_key,request_hash,order_id,operator_id,user_id,created_at,timezone,evidence) values("+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+",?,?,?,?,?,?,?,?)",r.idempotencyKey,hash,order,operator,r.userId,now,r.timezone,encode(evidence));
                if(actor!=null) {
                    Map<String,Object> detail=new LinkedHashMap<>();detail.put("userId",r.userId);detail.put("walletEnabled",r.walletEnabled);detail.put("historyEnabled",r.historyEnabled);detail.put("delta",r.walletEnabled?v.get("net"):BigDecimal.ZERO);detail.put("availableBefore",p.available);detail.put("availableAfter",evidence.get("walletAfter"));
                    org.springframework.web.context.request.RequestAttributes attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
                    String remote=attributes instanceof org.springframework.web.context.request.ServletRequestAttributes?((org.springframework.web.context.request.ServletRequestAttributes)attributes).getRequest().getRemoteAddr():null;
                    // Same physical connection: audit insertion failure rolls back order, wallet and historical adjustments.
                    db.update("insert into control_audit_log(actor_id,tenant_id,access_session_id,request_id,action,object_ref,outcome,detail,remote_address,created_at) values(?,?,?,?,'MANUAL_ORDER_CREATE',?,'SUCCESS',?,?,UTC_TIMESTAMP(6))",actor.getActorId(),actor.getTenantId(),actor.getAccessSessionId(),UUID.randomUUID().toString(),order.toString(),encode(detail),remote);
                }
                checkpoint("audit");
                c.commit();evidence.put("orderId",order);return evidence;
            } catch(RuntimeException | SQLException e) {if(!c.getAutoCommit())c.rollback();throw e;}
            finally {
                try {Collections.reverse(locks);for(String name:locks)db.queryForObject("select release_lock(?)",Integer.class,name);}
                finally {c.setAutoCommit(auto);c.setTransactionIsolation(isolation);}
            }
        } catch(SQLException e) {throw new IllegalStateException("Manual order transaction failed",e);}
    }
    private void lock(JdbcTemplate db,List<String> locks,String name) {
        name="tenant_"+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+"_"+name;
        if(!Integer.valueOf(1).equals(db.queryForObject("select get_lock(?,2)",Integer.class,name)))throw new BusinessException("锁忙，请使用原幂等键重试");
        locks.add(name);
    }
    private BigDecimal maxLeverage(BigDecimal configured,String category) {
        return ManualOrderCalculation.maxLeverage(categories.leverageEnabled(category)?configured:BigDecimal.ONE);
    }

    private Map<String,Object> emptyAccount() {
        Map<String,Object> a=new HashMap<>();a.put("available",BigDecimal.ZERO);a.put("row_version",-1L);return a;
    }
    public Map<String,Object> generateSimple(SimpleManualOrderGenerator.Request r) {
        authorize();
        if(r==null || r.symbol==null || r.symbol.length()>32 || r.userId!=null && r.userId<=0)throw new BusinessException("请选择有效品种或用户");
        if(r.userId==null && (r.walletEnabled || r.historyEnabled))throw new BusinessException("未绑定订单不能入钱包或回填历史权益");
        if(r.historyEnabled && !r.walletEnabled)throw new BusinessException("历史开启必须同时开启钱包入账");
        if(r.timezone==null) {
            List<String> zones=new JdbcTemplate(dataSource).query("select config_value from system_config where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and config_key='system.timezone'",(row,n)->row.getString(1));
            r.timezone=zones.isEmpty()?"Europe/London":zones.get(0);
        }
        ZoneId zone;try {zone=ZoneId.of(r.timezone);}catch(DateTimeException e){throw new BusinessException("时区无效");}
        TradingSymbol s=symbols.findByTenantIdAndSymbol(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),r.symbol).orElseThrow(()->new BusinessException("品种不存在"));
        if(!Boolean.TRUE.equals(s.getIsEnabled()))throw new BusinessException("品种已停用");
        QuantityRules.protocol(s,r.specVersion,r.quantityUnitType);
        BigDecimal available=r.userId==null?BigDecimal.ZERO:(BigDecimal)account(new JdbcTemplate(dataSource),r.userId,false).get("available");
        long end=Math.floorDiv(System.currentTimeMillis(),60000)*60000,from=end-ManualOrderGenerator.RANGE;
        SimpleManualOrderGenerator.validateTimes(r,from,end);
        BigDecimal maximum=maxLeverage(s.getMaxLeverage(),s.getCategory());SimpleManualOrderGenerator.validate(r,s,maximum);
        NavigableMap<Long,ManualOrderGenerator.Candle> candles;
        SimpleManualOrderGenerator.Candidate best=null;
        if(r.openTime!=null && r.closeTime!=null) candles=prices.selectedCandles(s,r.openTime,r.closeTime);
        else if(r.openTime==null && r.closeTime==null && r.closePrice==null) {
            // The recent two UTC windows usually contain a solution; older history is read only after a genuine miss.
            long recentFrom=Math.max(from,Math.floorDiv(end-1,720*60000L)*720*60000L-720*60000L);
            candles=prices.simpleCandles(s,recentFrom,end);
            try {best=SimpleManualOrderGenerator.solve(r,candles,s,available,maximum);}
            catch(BusinessException miss){if(miss.getCode()!=SimpleManualOrderGenerator.NO_SOLUTION)throw miss;}
            if(best==null && from<recentFrom)candles.putAll(prices.simpleCandles(s,from,recentFrom));
        } else candles=prices.simpleCandles(s,from,end);
        if(best==null)best=SimpleManualOrderGenerator.solve(r,candles,s,available,maximum);
        Request generated=new Request();generated.simpleMode=true;generated.allowNetAdjustment=r.allowNetAdjustment;generated.netTolerance=r.netTolerance;
        generated.userId=r.userId;generated.symbol=r.symbol;generated.timezone=r.timezone;generated.side=best.side;generated.leverage=best.leverage;
        generated.specVersion=r.specVersion;generated.quantityUnitType=r.quantityUnitType;generated.driver="QUANTITY";generated.input=best.calculation.get("quantity");generated.targetNet=r.targetNet;
        generated.openPrice=best.openPrice;generated.closePrice=best.closePrice;generated.walletEnabled=r.walletEnabled;generated.historyEnabled=r.historyEnabled;
        ZonedDateTime a=Instant.ofEpochMilli(best.open.time).atZone(zone),b=Instant.ofEpochMilli(best.close.time).atZone(zone);
        generated.openLocal=a.toLocalDateTime().toString();generated.openOffset=a.getOffset().toString();generated.closeLocal=b.toLocalDateTime().toString();generated.closeOffset=b.getOffset().toString();
        Map<String,Object> out=preview(generated),info=new LinkedHashMap<>();
        BigDecimal actualNet=new BigDecimal(((Map<?,?>)out.get("calculation")).get("net").toString());
        info.put("targetNet",r.targetNet);info.put("difference",actualNet.subtract(r.targetNet==null?actualNet:r.targetNet));
        info.put("errorPercent",ManualOrderGenerator.error(actualNet,r.targetNet));info.put("from",Instant.ofEpochMilli(from).toString());info.put("to",Instant.ofEpochMilli(end).toString());
        info.put("closeAutomaticallySelected",r.closePrice==null && r.closeTime==null);info.put("durationMinutes",(best.close.time-best.open.time)/60000);Map<String,Object> original=json.convertValue(r,Map.class);
        original.put("openPrice",r.openPrice);original.put("closePrice",r.closePrice);original.put("leverage",r.leverage);original.put("quantity",r.quantity);original.put("targetNet",r.targetNet);original.put("netTolerance",r.netTolerance);
        info.put("originalConditions",decimalStrings(original));
        out.put("generation",decimalStrings(info));return out;
    }

    public static class BindRequest {
        public Long userId;
        public boolean walletEnabled,historyEnabled;
        public String previewToken,idempotencyKey;
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String key,Object value) {throw new BusinessException("不支持的绑定参数: "+key);}
    }
    private static class BindingPreview {
        long operator,expires,order,orderVersion,accountVersion,close;
        Long tenant;String hash;BigDecimal available,net;Map<String,Object> orderSnapshot;
    }
    private void bindingInput(BindRequest r) {
        if(r==null || r.userId==null || r.userId<=0)throw new BusinessException("请选择绑定用户");
        if(r.historyEnabled && !r.walletEnabled)throw new BusinessException("历史开启必须同时开启钱包入账");
    }
    private String bindingHash(long order,BindRequest r,long operator) {
        return digest(order+":"+r.userId+":"+r.walletEnabled+":"+r.historyEnabled+":"+operator+":"+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
    }
    private Map<String,Object> unboundOrder(JdbcTemplate db,long order,boolean lock) {
        List<Map<String,Object>> rows=db.queryForList("select * from contract_order where tenant_id=? and id=?"+(lock?" for update":""),com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),order);
        if(rows.isEmpty())throw new BusinessException("订单不存在");Map<String,Object> o=rows.get(0);
        if(o.get("user_id")!=null || !"MANUAL_TEST".equals(o.get("order_source")) || !"CLOSED".equals(o.get("status")) || o.get("deleted_at")!=null)throw new BusinessException("仅未删除、未绑定的手动已平仓订单可以绑定");
        return o;
    }
    public Map<String,Object> previewBinding(long order,BindRequest r) {
        long operator=authorize();bindingInput(r);JdbcTemplate db=new JdbcTemplate(dataSource);
        Map<String,Object> o=unboundOrder(db,order,false),account=account(db,r.userId,false);
        if(db.queryForList("select id from user_account where tenant_id=? and id=?",com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(),r.userId).isEmpty())throw new BusinessException("用户不存在");
        BindingPreview p=new BindingPreview();p.operator=operator;p.tenant=com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();p.order=order;p.orderVersion=((Number)o.get("row_version")).longValue();p.accountVersion=((Number)account.get("row_version")).longValue();
        p.orderSnapshot=new LinkedHashMap<>(o);p.available=(BigDecimal)account.get("available");p.net=ManualOrderCalculation.money(((BigDecimal)o.get("profit")).subtract((BigDecimal)o.get("fee")));
        Object closed=o.get("close_time");
        if(!(closed instanceof LocalDateTime) && !(closed instanceof Timestamp))throw new BusinessException("订单平仓时间无效，不能绑定");
        p.close=(closed instanceof LocalDateTime?(LocalDateTime)closed:((Timestamp)closed).toLocalDateTime()).toInstant(ZoneOffset.UTC).toEpochMilli();p.hash=bindingHash(order,r,operator);p.expires=System.currentTimeMillis()+300000;
        Map<String,Object> out=new LinkedHashMap<>();out.put("orderId",order);out.put("userId",r.userId);out.put("net",p.net.toPlainString());out.put("walletBefore",p.available.toPlainString());
        BigDecimal after=p.available.add(r.walletEnabled?p.net:BigDecimal.ZERO);if(after.signum()<0)throw new BusinessException("绑定入账后钱包余额不能为负");out.put("walletAfter",after.toPlainString());
        out.put("openTime",o.get("open_time"));out.put("closeTime",o.get("close_time"));out.put("walletEnabled",r.walletEnabled);out.put("historyEnabled",r.historyEnabled);out.put("history",r.historyEnabled?history.inspect(db,r.userId,p.close):null);
        bindingPreviews.entrySet().removeIf(e->e.getValue().expires<System.currentTimeMillis());if(bindingPreviews.size()>=1000)throw new BusinessException("绑定预览繁忙，请稍后重试");
        String token=UUID.randomUUID().toString();bindingPreviews.put(token,p);out.put("previewToken",token);return out;
    }
    public Map<String,Object> bind(long order,BindRequest r) {
        long operator=authorize();bindingInput(r);
        if(r.idempotencyKey==null || !r.idempotencyKey.matches("[A-Za-z0-9_-]{16,64}"))throw new BusinessException("幂等键无效");
        String expected=bindingHash(order,r,operator);Long tenant=com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        try(Connection c=dataSource.getConnection()) {
            boolean auto=c.getAutoCommit();int isolation=c.getTransactionIsolation();List<String> locks=new ArrayList<>();JdbcTemplate db=new JdbcTemplate(new SingleConnectionDataSource(c,true));db.setQueryTimeout(15);
            try {
                lock(db,locks,"manual_bind_"+digest(r.idempotencyKey).substring(0,24));
                List<Map<String,Object>> saved=db.queryForList("select request_hash,evidence from manual_order_binding where tenant_id=? and idempotency_key=?",tenant,r.idempotencyKey);
                if(!saved.isEmpty()) {if(!expected.equals(saved.get(0).get("request_hash")))throw new BusinessException("同一幂等键不能绑定不同内容");return json.readValue(saved.get(0).get("evidence").toString(),Map.class);}
                BindingPreview p=bindingPreviews.get(r.previewToken==null?"":r.previewToken);
                if(p==null || p.expires<System.currentTimeMillis() || !tenant.equals(p.tenant) || p.operator!=operator || !expected.equals(p.hash))throw new BusinessException("绑定预览过期或参数已变更，请重新预览");
                if(r.historyEnabled) {lock(db,locks,"equity_v1_capture");lock(db,locks,"equity_v1_rollup");}
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
                Map<String,Object> o=unboundOrder(db,order,true);
                if(((Number)o.get("row_version")).longValue()!=p.orderVersion || !o.equals(p.orderSnapshot))throw new BusinessException("订单已变化，请重新预览");
                if(db.queryForList("select id from user_account where tenant_id=? and id=? for update",tenant,r.userId).isEmpty())throw new BusinessException("用户不存在");
                Map<String,Object> account=account(db,r.userId,true);
                if(((Number)account.get("row_version")).longValue()!=p.accountVersion || ((BigDecimal)account.get("available")).compareTo(p.available)!=0)throw new BusinessException("钱包已变化，请重新预览");
                if(db.update("update contract_order set user_id=?,manual_wallet_enabled=?,manual_equity_enabled=?,row_version=row_version+1,updated_at=UTC_TIMESTAMP(6) where tenant_id=? and id=? and user_id is null and row_version=?",r.userId,r.walletEnabled,r.historyEnabled,tenant,order,p.orderVersion)!=1)throw new BusinessException("订单绑定冲突");
                checkpoint("binding");
                if(r.walletEnabled) {
                    if(p.available.add(p.net).signum()<0)throw new BusinessException("绑定入账后钱包余额不能为负");
                    if(db.update("update asset_account set available=available+?,row_version=row_version+1,updated_at=UTC_TIMESTAMP() where tenant_id=? and id=? and row_version=?",p.net,tenant,account.get("id"),p.accountVersion)!=1)throw new BusinessException("钱包版本冲突");
                }
                checkpoint("wallet");Map<String,Object> out=new LinkedHashMap<>();out.put("orderId",order);out.put("userId",r.userId);out.put("net",p.net.toPlainString());out.put("walletBefore",p.available.toPlainString());out.put("walletAfter",p.available.add(r.walletEnabled?p.net:BigDecimal.ZERO).toPlainString());
                out.put("walletEnabled",r.walletEnabled);out.put("historyEnabled",r.historyEnabled);out.put("history",r.historyEnabled?history.apply(db,r.userId,p.close,p.net,System.currentTimeMillis(),this::checkpoint):null);
                com.gtcfesk.exchange.control.ControlIdentity actor=com.gtcfesk.exchange.control.ControlIdentity.current();out.put("actorType",actor==null?"ADMIN":"CONTROL");out.put("actorId",actor==null?operator:actor.getActorId());out.put("accessSessionId",actor==null?null:actor.getAccessSessionId());
                db.update("insert into manual_order_binding(tenant_id,order_id,user_id,operator_id,idempotency_key,request_hash,created_at,evidence) values(?,?,?,?,?,?,?,?)",tenant,order,r.userId,operator,r.idempotencyKey,expected,System.currentTimeMillis(),encode(out));
                if(actor!=null)db.update("insert into control_audit_log(actor_id,tenant_id,access_session_id,request_id,action,object_ref,outcome,detail,created_at) values(?,?,?,?,'MANUAL_ORDER_BIND',?,'SUCCESS',?,UTC_TIMESTAMP(6))",actor.getActorId(),tenant,actor.getAccessSessionId(),UUID.randomUUID().toString(),Long.toString(order),encode(out));
                checkpoint("audit");c.commit();return out;
            }catch(RuntimeException | SQLException e) {if(!c.getAutoCommit())c.rollback();throw e;}
            catch(java.io.IOException e) {if(!c.getAutoCommit())c.rollback();throw new IllegalStateException("绑定审计记录无法读取",e);}
            finally {try {Collections.reverse(locks);for(String name:locks)db.queryForObject("select release_lock(?)",Integer.class,name);}finally {c.setAutoCommit(auto);c.setTransactionIsolation(isolation);}}
        }catch(SQLException e){throw new IllegalStateException("Binding transaction failed",e);}
    }
}
