package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service @RequiredArgsConstructor
public class MarketHoursService {
    private final SystemConfigService configs;
    private final TradingSymbolRepository symbols;
    private final MarketCategoryService categories;
    private final com.gtcfesk.exchange.control.TenantRepository tenants;
    private final Map<Long,Cached> cache=new ConcurrentHashMap<>();
    private static class Cached {
        final long expires;final MarketHoursConfig.Settings settings;
        Cached(MarketHoursConfig.Settings settings){this.settings=settings;expires=System.currentTimeMillis()+1000;}
    }
    private MarketHoursConfig.Settings current() {
        Long tenant=TenantContext.requireTenantId();
        // ponytail: one-second tenant-scoped read-through cache; distributed invalidation only if sub-second multi-node changes are required.
        return cache.compute(tenant,(id,old)->old!=null&&System.currentTimeMillis()<old.expires?old:
                new Cached(MarketHoursConfig.parse(configs.getConfigValue(MarketHoursConfig.KEY)))).settings;
    }
    public MarketHoursConfig.Status status(TradingSymbol symbol) {return MarketHoursConfig.evaluate(current(),symbol,Instant.now());}
    public void requireOpen(TradingSymbol symbol) {
        MarketHoursConfig.Status status=status(symbol);if(status.closed)throw MarketHoursConfig.bad("当前休市（"+status.reason+"）");
    }
    public void requireWindow(TradingSymbol symbol,int seconds) {
        MarketHoursConfig.Status status=status(symbol);
        if(status.closed)throw MarketHoursConfig.bad("当前休市（"+status.reason+"）");
        if(status.nextChangeAt!=null&&Instant.now().plusSeconds(seconds).toEpochMilli()>=status.nextChangeAt)
            throw MarketHoursConfig.bad("该交易周期跨越计划休市，请缩短周期或等待开市");
    }
    public Map<String,Object> view() {
        MarketHoursConfig.Settings settings=MarketHoursConfig.parse(configs.getConfigValue(MarketHoursConfig.KEY));
        Map<String,Object> out=new LinkedHashMap<>();out.put("settings",settings);out.put("categories",categories.all());
        List<Map<String,Object>> rows=new ArrayList<>();Instant now=Instant.now();
        for(TradingSymbol s:symbols.findAllByTenantId(TenantContext.requireTenantId())) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("id",s.getId());row.put("symbol",s.getSymbol());row.put("name",s.getDisplayName());
            row.put("category",s.getCategory());row.put("sourceCategory",s.getSourceCategory());row.put("isEnabled",s.getIsEnabled());
            row.put("status",MarketHoursConfig.evaluate(settings,s,now));rows.add(row);
        }
        Set<String> existing=new HashSet<>();for(Map<String,Object> row:rows)existing.add(String.valueOf(row.get("id")));
        settings.symbols.keySet().retainAll(existing); // Deleted instrument bindings disappear on the next successful save.
        out.put("symbols",rows);out.put("serverTime",now.toEpochMilli());return out;
    }
    @Transactional public Map<String,Object> save(MarketHoursConfig.Settings input) {
        MarketHoursConfig.validate(input);
        tenants.lock(TenantContext.requireTenantId()).orElseThrow(()->MarketHoursConfig.bad("租户不存在"));
        MarketHoursConfig.Settings old=MarketHoursConfig.parse(configs.getConfigValue(MarketHoursConfig.KEY));
        pruneDeletedSymbols(old);
        if(input.revision!=old.revision)throw MarketHoursConfig.bad("配置已被其他管理员修改，请重新加载");
        // Policy editing must not silently overwrite live manual interventions.
        if(!rulesEqual(input.categories,old.categories)||!rulesEqual(input.symbols,old.symbols))throw MarketHoursConfig.bad("手动模式请使用手动开关操作");
        checkTargets(input);input.revision++;persist(input);return view();
    }
    private boolean rulesEqual(Map<String,MarketHoursConfig.Rule> a,Map<String,MarketHoursConfig.Rule> b) {
        Set<String> keys=new HashSet<>(a.keySet());keys.addAll(b.keySet());
        for(String key:keys){MarketHoursConfig.Rule x=a.getOrDefault(key,new MarketHoursConfig.Rule()),y=b.getOrDefault(key,new MarketHoursConfig.Rule());
            if(!Objects.equals(x.mode,y.mode)||!Objects.equals(x.until,y.until)||!Objects.equals(x.reason,y.reason))return false;
        }return true;
    }
    public static class OverrideInput {
        public String scope, target, mode, until, reason;
        public long revision;
    }
    @Transactional public Map<String,Object> override(OverrideInput input) {
        if(input==null||!Arrays.asList("CATEGORY","SYMBOL").contains(input.scope))throw MarketHoursConfig.bad("对象类型无效");
        tenants.lock(TenantContext.requireTenantId()).orElseThrow(()->MarketHoursConfig.bad("租户不存在"));
        MarketHoursConfig.Settings settings=MarketHoursConfig.parse(configs.getConfigValue(MarketHoursConfig.KEY));
        pruneDeletedSymbols(settings);
        if(input.revision!=settings.revision)throw MarketHoursConfig.bad("配置已变更，请重新加载");
        Map<String,MarketHoursConfig.Rule> rules="CATEGORY".equals(input.scope)?settings.categories:settings.symbols;
        MarketHoursConfig.Rule r=rules.computeIfAbsent(input.target,k->new MarketHoursConfig.Rule());
        r.mode=input.mode;r.until=input.until;r.reason=input.reason;
        if("AUTO".equals(r.mode)){r.until=null;r.reason="";}
        else if(r.until!=null&&(!MarketHoursConfig.instant(r.until).isAfter(Instant.now())
                ||"OPEN".equals(r.mode)&&MarketHoursConfig.instant(r.until).isAfter(Instant.now().plusSeconds(86400))))throw MarketHoursConfig.bad("失效时间须在未来；手动开市最长24小时");
        checkTargets(settings);settings.revision++;persist(settings);return view();
    }
    private void checkTargets(MarketHoursConfig.Settings settings) {
        MarketHoursConfig.validate(settings);
        Set<String> keys=new HashSet<>();for(Map<String,Object> c:categories.all())keys.add((String)c.get("key"));
        // The built-in Forex fallback remains usable when a tenant only has renamed display categories.
        keys.add("Forex");if(!keys.containsAll(settings.categories.keySet()))throw MarketHoursConfig.bad("分类不存在");
        Set<String> ids=new HashSet<>();for(TradingSymbol s:symbols.findAllByTenantId(TenantContext.requireTenantId()))ids.add(String.valueOf(s.getId()));
        if(!ids.containsAll(settings.symbols.keySet()))throw MarketHoursConfig.bad("品种不存在或不属于当前租户");
    }
    private void pruneDeletedSymbols(MarketHoursConfig.Settings settings) {
        Set<String> ids=new HashSet<>();for(TradingSymbol s:symbols.findAllByTenantId(TenantContext.requireTenantId()))ids.add(String.valueOf(s.getId()));
        settings.symbols.keySet().retainAll(ids);
    }
    private void persist(MarketHoursConfig.Settings settings) {
        configs.saveConfig(MarketHoursConfig.KEY,MarketHoursConfig.encode(settings),"分类休市策略与品种覆盖");
        Long tenant=TenantContext.requireTenantId();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){cache.remove(tenant);}});
    }
}
