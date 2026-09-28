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
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final TradingSymbolRepository symbols;
    private final ManualOrderPrices prices;
    private final ManualOrderHistory history;
    @Value("${manual.orders.enabled:false}") private boolean enabled;
    private final Map<String,Preview> previews=new ConcurrentHashMap<>();
    public static class Request {
        public Long userId;
        public String symbol,side,timezone,openLocal,closeLocal,openOffset,closeOffset,driver;
        public BigDecimal input,leverage;
        public boolean walletEnabled,historyEnabled;
        public String previewToken,idempotencyKey;
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String key,Object value) {throw new BusinessException("不支持的手动订单参数: "+key);}
    }
    private static class Preview {
        long operator,expires,open,close,accountVersion,symbolVersion,symbolId;
        String hash,currency,source;BigDecimal available,lot,fee;
        Map<String,Object> quotes;Map<String,BigDecimal> calculation;
    }
    private long authorize() {
        Authentication a=SecurityContextHolder.getContext().getAuthentication();
        if(a==null || a.getAuthorities().stream().noneMatch(r->r.getAuthority().equals("ROLE_SUPER_ADMIN"))) throw new AccessDeniedException("仅限内测超级管理员");
        if(!enabled) throw new BusinessException("手动订单内测开关未开启");
        return Long.parseLong(a.getName());
    }
    public Map<String,Object> context(Long user,String search) {
        authorize();JdbcTemplate db=new JdbcTemplate(dataSource);Map<String,Object> out=new LinkedHashMap<>();
        String q=search==null?"":search.trim();
        out.put("users",db.queryForList("select id,email from user_account where cast(id as char)=? or email like ? order by id desc limit 30",q,"%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"));
        out.put("symbols",db.queryForList("select symbol,name,lot_size,fee_multiplier from trading_symbol where is_enabled=1 order by sort_order,id"));
        List<String> zones=db.query("select config_value from system_config where config_key='system.timezone'",(r,n)->r.getString(1));
        out.put("timezone",zones.isEmpty()?"Europe/London":zones.get(0));out.put("maxHistoryRows",ManualOrderHistory.MAX_ROWS);
        if(user!=null) {out.put("user",db.queryForMap("select id,email from user_account where id=?",user));out.put("account",account(db,user,false));}
        return out;
    }
    private Map<String,Object> account(JdbcTemplate db,long user,boolean lock) {
        List<Map<String,Object>> rows=db.queryForList("select id,available,frozen,row_version from asset_account where user_id=? and coin='CONTRACT'"+(lock?" for update":""),user);
        if(rows.isEmpty()) throw new BusinessException("用户尚未建立合约钱包，请先初始化测试账户");
        return rows.get(0);
    }
    public Map<String,Object> preview(Request r) {
        long operator=authorize(),now=System.currentTimeMillis();validate(r,now);
        long open=ManualOrderCalculation.minute(r.openLocal,r.timezone,r.openOffset),close=ManualOrderCalculation.minute(r.closeLocal,r.timezone,r.closeOffset);
        TradingSymbol s=symbols.findBySymbol(r.symbol).orElseThrow(()->new BusinessException("品种不存在"));
        if(!Boolean.TRUE.equals(s.getIsEnabled()))throw new BusinessException("品种已停用");
        Preview p=new Preview(),old=r.previewToken==null?null:previews.get(r.previewToken);
        p.quotes=old!=null && old.operator==operator && old.expires>now && old.symbolId==s.getId() && old.symbolVersion==s.getRowVersion() && old.open==open && old.close==close
                ?old.quotes:prices.quote(s,open,close);
        JdbcTemplate db=new JdbcTemplate(dataSource);Map<String,Object> account=account(db,r.userId,false);
        p.operator=operator;p.open=open;p.close=close;p.expires=now+300000;p.hash=hash(r,operator);
        p.accountVersion=((Number)account.get("row_version")).longValue();p.available=(BigDecimal)account.get("available");
        p.symbolId=s.getId();p.symbolVersion=s.getRowVersion();p.lot=s.getLotSize()==null?new BigDecimal("1000"):s.getLotSize();p.fee=s.getFeeMultiplier()==null?new BigDecimal("30"):s.getFeeMultiplier();
        p.currency=s.getQuoteCurrency();p.source=s.getMarketSource();p.calculation=calculate(r,p);
        Map<String,Object> out=evidence(r,p);out.put("history",r.historyEnabled?history.inspect(db,r.userId,close):null);
        previews.entrySet().removeIf(e->e.getValue().expires<=now);
        // ponytail: bounded in-process previews; restart requires preview again, committed retries are durable.
        if(previews.size()>=1000)throw new BusinessException("预览繁忙，请稍后重试");
        String token=UUID.randomUUID().toString();previews.put(token,p);out.put("previewToken",token);return out;
    }
    private void validate(Request r,long now) {
        if(r==null || r.userId==null || r.userId<=0 || r.symbol==null || r.symbol.length()>32)throw new BusinessException("用户或品种无效");
        if(r.historyEnabled && !r.walletEnabled)throw new BusinessException("历史开启必须同时开启钱包入账");
        long open=ManualOrderCalculation.minute(r.openLocal,r.timezone,r.openOffset),close=ManualOrderCalculation.minute(r.closeLocal,r.timezone,r.closeOffset);
        if(open>close || close>now || open<0)throw new BusinessException("开平仓时间顺序无效或使用未来时间");
        if(r.historyEnabled && close+60000>now)throw new BusinessException("历史开启时请等待平仓分钟结束");
    }
    private BigDecimal decimal(Preview p,String name) {return new BigDecimal(p.quotes.get(name).toString());}
    private Map<String,BigDecimal> calculate(Request r,Preview p) {
        return ManualOrderCalculation.calculate(r.driver,r.input,r.side,p.available,decimal(p,"openPrice"),decimal(p,"closePrice"),p.lot,r.leverage,decimal(p,"openRate"),decimal(p,"closeRate"),p.fee);
    }
    private Map<String,Object> evidence(Request r,Preview p) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("request",json.convertValue(r,Map.class));out.put("quotes",p.quotes);out.put("calculation",p.calculation);
        out.put("walletBefore",p.available);out.put("walletAfter",ManualOrderCalculation.money(p.available.add(r.walletEnabled?p.calculation.get("net"):BigDecimal.ZERO)));
        out.put("accountVersion",p.accountVersion);out.put("symbolVersion",p.symbolVersion);out.put("lotSize",p.lot);out.put("feePerLot",p.fee);out.put("expiresAt",p.expires);
        out.put("openUtc",Instant.ofEpochMilli(p.open).toString());out.put("closeUtc",Instant.ofEpochMilli(p.close).toString());
        out.put("openOffset",Instant.ofEpochMilli(p.open).atZone(ZoneId.of(r.timezone)).getOffset().toString());out.put("closeOffset",Instant.ofEpochMilli(p.close).atZone(ZoneId.of(r.timezone)).getOffset().toString());
        return out;
    }
    private String hash(Request r,long operator) {
        Map<String,Object> fields=new TreeMap<>(json.convertValue(r,Map.class));fields.remove("previewToken");fields.remove("idempotencyKey");fields.put("operator",operator);
        return digest(encode(fields));
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
                lock(db,locks,"manual_"+digest(r.idempotencyKey).substring(0,48));
                // Durable replay must precede preview expiry and account-version checks.
                List<Map<String,Object>> saved=db.queryForList("select request_hash,order_id from manual_order_record where idempotency_key=?",r.idempotencyKey);
                if(!saved.isEmpty()) {
                    if(!hash.equals(saved.get(0).get("request_hash")))throw new BusinessException("同一幂等键不能提交不同内容");
                    return Collections.singletonMap("orderId",saved.get(0).get("order_id"));
                }
                validate(r,System.currentTimeMillis());Preview p=previews.get(r.previewToken==null?"":r.previewToken);
                if(p==null || p.expires<System.currentTimeMillis() || p.operator!=operator || !p.hash.equals(hash))throw new BusinessException("预览过期或参数已变更，请重新预览");
                if(r.historyEnabled) {lock(db,locks,"equity_v1_capture");lock(db,locks,"equity_v1_rollup");}
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
                if(db.queryForList("select id from user_account where id=? for update",r.userId).isEmpty())throw new BusinessException("用户不存在");
                Map<String,Object> account=account(db,r.userId,true);
                if(((Number)account.get("row_version")).longValue()!=p.accountVersion || ((BigDecimal)account.get("available")).compareTo(p.available)!=0)throw new BusinessException("钱包已变化，请重新预览");
                List<Map<String,Object>> configs=db.queryForList("select row_version from trading_symbol where id=? and is_enabled=1 lock in share mode",p.symbolId);
                if(configs.isEmpty() || ((Number)configs.get(0).get("row_version")).longValue()!=p.symbolVersion)throw new BusinessException("品种配置已变化，请重新预览");
                p.calculation=calculate(r,p);Map<String,BigDecimal> v=p.calculation;long now=System.currentTimeMillis();
                db.update("insert into contract_order(user_id,symbol,side,type,status,order_source,manual_wallet_enabled,manual_equity_enabled,quantity,leverage,lot_size,margin,fee,profit,open_price,close_price,current_price,open_time,close_time,created_at,updated_at,row_version,limit_match_enabled,quote_currency,quote_source,margin_conversion_rate,settlement_conversion_rate) values(?,?,?,'MARKET','CLOSED','MANUAL_TEST',?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0,0,?,?,?,?)",
                        r.userId,r.symbol,r.side,r.walletEnabled,r.historyEnabled,v.get("quantity"),r.leverage,p.lot,v.get("margin"),v.get("fee"),v.get("profit"),decimal(p,"openPrice"),decimal(p,"closePrice"),decimal(p,"closePrice"),LocalDateTime.ofInstant(Instant.ofEpochMilli(p.open),ZoneOffset.UTC).toString(),LocalDateTime.ofInstant(Instant.ofEpochMilli(p.close),ZoneOffset.UTC).toString(),p.currency,p.source,decimal(p,"openRate"),decimal(p,"closeRate"));
                Long order=db.queryForObject("select last_insert_id()",Long.class);checkpoint("order");
                if(r.walletEnabled) {
                    ManualOrderCalculation.money(p.available.add(v.get("net")));
                    if(db.update("update asset_account set available=available+?,row_version=row_version+1,updated_at=UTC_TIMESTAMP() where id=? and row_version=?",v.get("net"),account.get("id"),p.accountVersion)!=1)throw new BusinessException("钱包版本冲突，请重新预览");
                }
                checkpoint("wallet");Map<String,Object> evidence=evidence(r,p);
                evidence.put("history",r.historyEnabled?history.apply(db,r.userId,p.close,v.get("net"),now,this::checkpoint):null);
                db.update("insert into manual_order_record(idempotency_key,request_hash,order_id,operator_id,user_id,created_at,timezone,evidence) values(?,?,?,?,?,?,?,?)",r.idempotencyKey,hash,order,operator,r.userId,now,r.timezone,encode(evidence));checkpoint("audit");
                c.commit();evidence.put("orderId",order);return evidence;
            } catch(RuntimeException | SQLException e) {if(!c.getAutoCommit())c.rollback();throw e;}
            finally {
                try {Collections.reverse(locks);for(String name:locks)db.queryForObject("select release_lock(?)",Integer.class,name);}
                finally {c.setAutoCommit(auto);c.setTransactionIsolation(isolation);}
            }
        } catch(SQLException e) {throw new IllegalStateException("Manual order transaction failed",e);}
    }
    private void lock(JdbcTemplate db,List<String> locks,String name) {
        if(!Integer.valueOf(1).equals(db.queryForObject("select get_lock(?,2)",Integer.class,name)))throw new BusinessException("锁忙，请使用原幂等键重试");
        locks.add(name);
    }
}
