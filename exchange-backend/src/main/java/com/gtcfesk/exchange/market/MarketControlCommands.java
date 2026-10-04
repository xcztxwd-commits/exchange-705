package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.OrderRequest;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.control.ControlIdentity;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantJobRunner;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/** Durable queue; only two preparation workers, at most one queued/running turn per tenant. */
@Service
public class MarketControlCommands {
    private final ControlHistoryStore store;private final ForexQuoteMarketService market;
    private final TenantJobRunner jobs;private final ControlAuditService audit;
    private final ObjectMapper json=new ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Set<Long> active=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->new Thread(r,"market-prepare"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService polling=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"market-command-queue"));
    private final java.util.concurrent.atomic.AtomicLong rejected=new java.util.concurrent.atomic.AtomicLong();
    public MarketControlCommands(ControlHistoryStore store,ForexQuoteMarketService market,TenantJobRunner jobs,ControlAuditService audit){this.store=store;this.market=market;this.jobs=jobs;this.audit=audit;}
    @PostConstruct void start(){polling.scheduleWithFixedDelay(this::poll,1,1,TimeUnit.SECONDS);}
    @PreDestroy void stop(){polling.shutdownNow();workers.shutdownNow();}
    private void poll(){
        try{jobs.eachContext("market-command-poll",tenant->{
            if(!active.add(tenant))return;
            try{workers.execute(()->{try{jobs.oneContext("market-command",tenant,this::runOne);}finally{active.remove(tenant);}});}
            catch(RejectedExecutionException full){active.remove(tenant);rejected.incrementAndGet();}
        });}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Command polling failed: {}",failure.getClass().getSimpleName());}
    }
    public Map<String,Object> accept(long symbol,int duration,BigDecimal target,int intensity,boolean oscillation,String requestKey,TargetControlOptions options){
        String key=OrderRequest.required(requestKey);
        market.commandConfig(symbol);
        ControlIdentity actor=operator();
        if(duration<1 || duration>86400 || intensity<1 || intensity>10 || target==null || target.signum()<=0 || target.compareTo(BigDecimal.TEN.pow(16))>=0)
            throw new BusinessException("控盘参数或品种状态无效");
        Map<String,Object> parameters=new TreeMap<>(json.convertValue(options==null?new TargetControlOptions():options,Map.class));
        parameters.put("duration",duration);parameters.put("target",target.stripTrailingZeros().toPlainString());parameters.put("intensity",intensity);parameters.put("oscillation",oscillation);
        parameters.remove("durationSeconds");parameters.remove("targetPrice");parameters.remove("randomOscillation");parameters.remove("requestKey");
        parameters.replaceAll((name,value)->value instanceof BigDecimal?((BigDecimal)value).stripTrailingZeros().toPlainString():value);
        String hash=OrderRequest.hash(parameters);
        return store.transaction(()->{
            store.db.update("INSERT INTO market_engine_tenant(tenant_id) VALUES(?) ON DUPLICATE KEY UPDATE tenant_id=VALUES(tenant_id)",ControlHistoryStore.tenant());
            store.db.queryForObject("SELECT tenant_id FROM market_engine_tenant WHERE tenant_id=? FOR UPDATE",Long.class,ControlHistoryStore.tenant());
            List<Map<String,Object>> original=rows(symbol,key);
            if(!original.isEmpty()){OrderRequest.same((String)original.get(0).get("parameter_hash"),hash);return receipt(original.get(0));}
            if(store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=? AND state IN ('ACCEPTED','PREPARING','READY')",Integer.class,ControlHistoryStore.tenant())>=32){rejected.incrementAndGet();throw new BusinessException("COMMAND_QUEUE_FULL: 租户准备队列已满");}
            return store.locked(symbol,()->{
                TradingSymbol config=market.commandConfig(symbol);
                if(!Boolean.TRUE.equals(config.getIsEnabled()) || target.stripTrailingZeros().scale()>PriceControlPath.precision(config))throw new BusinessException("控盘参数或品种状态无效");
                if(store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=? AND symbol_id=? AND state IN ('ACCEPTED','PREPARING','READY')",Integer.class,ControlHistoryStore.tenant(),symbol)>0)throw new BusinessException("该品种已有准备中的启动命令");
                // Server-owned basis is persisted after hashing the request, so retries never use today's defaults.
                parameters.put("configBasis",configBasis(config));
                if(parameters.get("stepFormula")==null)parameters.put("stepFormula",market.controlFormula(symbol).get("stepFormula"));
                long now=store.runtime.clock();String id=UUID.randomUUID().toString();
                store.db.update("INSERT INTO market_control_command(tenant_id,id,symbol_id,request_key,parameter_hash,parameters_json,state,actor_id,session_id,config_revision,control_revision,seed,accepted_at,expires_at) VALUES(?,?,?,?,?,?,'ACCEPTED',?,?,?,?,?,?,?)",ControlHistoryStore.tenant(),id,symbol,key,hash,store.encode(parameters),actor.getActorId(),actor.getAccessSessionId(),config.getRowVersion(),store.runtime.revision(symbol),new java.security.SecureRandom().nextLong(),now,now+300000);
                audit.record(actor.getActorId(),actor.getTenantId(),actor.getAccessSessionId(),"ai-control-command.accept",id,"ACCEPTED","{}",null);
                return receipt(rows(symbol,key).get(0));
            });
        });
    }
    private ControlIdentity operator(){
        org.springframework.security.core.Authentication authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null && authentication.isAuthenticated()){
            ControlIdentity access=ControlIdentity.current();
            if(access!=null){if(access.getActorId()!=null && access.getActorId()>0 && Objects.equals(access.getTenantId(),ControlHistoryStore.tenant()))return access;}
            else{
                boolean agent=authentication.getAuthorities().stream().anyMatch(role->"ROLE_AGENT".equals(role.getAuthority()));
                boolean admin=authentication.getAuthorities().stream().anyMatch(role->Arrays.asList("ROLE_ADMIN","ROLE_SUPER_ADMIN").contains(role.getAuthority()));
                String principal=authentication.getName(),digits=agent && principal.startsWith("agent-")?principal.substring(6):!agent && admin?principal:"";
                if(digits.matches("[1-9][0-9]*"))try{return new ControlIdentity(Long.parseLong(digits),ControlHistoryStore.tenant(),null);}catch(NumberFormatException invalid){/* Fail closed below. */}
            }
        }
        throw new org.springframework.security.access.AccessDeniedException("启动命令需要当前租户后台操作者");
    }
    private List<Map<String,Object>> rows(long symbol,String key){return store.db.queryForList("SELECT * FROM market_control_command WHERE tenant_id=? AND symbol_id=? AND request_key=?",ControlHistoryStore.tenant(),symbol,key);}
    public Map<String,Object> query(long symbol,String key){market.commandConfig(symbol);List<Map<String,Object>> result=rows(symbol,OrderRequest.required(key));if(result.isEmpty())throw new BusinessException("启动命令不存在");return receipt(result.get(0));}
    private Map<String,Object> receipt(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();result.put("commandId",row.get("id"));result.put("requestKey",row.get("request_key"));result.put("symbolId",row.get("symbol_id"));result.put("state",row.get("state"));result.put("errorCode",row.get("error_code"));result.put("message",row.get("message"));result.put("taskId",row.get("task_id"));result.put("acceptedAt",row.get("accepted_at"));return result;}
    public void cancel(long symbol,String key){
        market.commandConfig(symbol);
        store.locked(symbol,()->{cancelLocked(symbol,key);return null;});
    }
    public Map<String,Object> stopControl(long symbol,String key){
        market.commandConfig(symbol);
        return store.locked(symbol,()->{cancelLocked(symbol,key);return market.stopControl(symbol);});
    }
    private void cancelLocked(long symbol,String key){
        store.runtime.invalidate(symbol);
        store.db.update("UPDATE market_control_command SET state='CANCELLED',prepared_json=NULL,error_code='CONTROL_CANCELLED',message='启动准备已取消' WHERE tenant_id=? AND symbol_id=? AND state IN ('ACCEPTED','PREPARING','READY')"+(key==null?"":" AND request_key=?"),key==null?new Object[]{ControlHistoryStore.tenant(),symbol}:new Object[]{ControlHistoryStore.tenant(),symbol,OrderRequest.required(key)});
    }
    void runOne(){
        List<Map<String,Object>> pending=store.db.queryForList("SELECT * FROM market_control_command WHERE tenant_id=? AND state IN ('ACCEPTED','PREPARING','READY') ORDER BY accepted_at,id LIMIT 32",ControlHistoryStore.tenant());
        // A busy/fenced symbol must not starve the rest of this tenant's bounded queue.
        for(Map<String,Object> command:pending)if(runCommand(command))return;
    }
    private boolean runCommand(Map<String,Object> command){
        long symbol=((Number)command.get("symbol_id")).longValue();String id=(String)command.get("id");boolean preparationStarted=false;
        try{
            boolean claimed=store.locked(symbol,()->{
                Map<String,Object> current=rows(symbol,(String)command.get("request_key")).get(0);
                if(!Arrays.asList("ACCEPTED","PREPARING","READY").contains(current.get("state")))return false;
                validate(current,symbol);
                long generation=store.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",Long.class,ControlHistoryStore.tenant(),symbol);
                // A new process resumes from the persisted seed, never accepts a late old Prepared.
                store.db.update("UPDATE market_control_command SET state='PREPARING',owner_id=?,writer_generation=? WHERE tenant_id=? AND id=?",store.runtime.owner,generation,ControlHistoryStore.tenant(),id);
                command.put("writer_generation",generation);return true;
            });
            if(!claimed)return false;preparationStarted=true;
            Map<String,Object> p=store.decode((String)command.get("parameters_json"));TargetControlOptions options=json.convertValue(p,TargetControlOptions.class);
            // Unknown extra persisted fields are excluded from bean conversion.
            int duration=((Number)p.get("duration")).intValue(),intensity=((Number)p.get("intensity")).intValue();BigDecimal target=new BigDecimal((String)p.get("target"));boolean oscillation=Boolean.TRUE.equals(p.get("oscillation"));
            try(PersistentPriceControl.Prepared prepared=market.prepareCommand(symbol,duration,target,intensity,oscillation,options,((Number)command.get("seed")).longValue())){
                Map<String,Object> ready=new LinkedHashMap<>();ready.put("parameters_json",prepared.encoded.parameters);ready.put("prices_json",prepared.encoded.prices);ready.put("summary_json",prepared.encoded.summary);ready.put("checksum",prepared.encoded.checksum);ready.put("seed",prepared.seed);ready.put("previousTaskId",prepared.previousTaskId);ready.put("start",prepared.start.toPlainString());
                store.locked(symbol,()->{validateClaim(command,symbol);store.db.update("UPDATE market_control_command SET state='READY',prepared_json=? WHERE tenant_id=? AND id=?",store.encode(ready),ControlHistoryStore.tenant(),id);return null;});
                store.withPlans(Collections.singletonList(symbol),()->store.locked(symbol,()->{
                    validateClaim(command,symbol);
                    market.activateCommand(symbol,duration,target,intensity,oscillation,(String)command.get("request_key"),options,prepared);
                    PersistentPriceControl.Task task=new PersistentPriceControl(store).latest(symbol);
                    store.db.update("UPDATE market_control_command SET state='RUNNING',task_id=?,prepared_json=NULL,error_code=NULL,message=NULL WHERE tenant_id=? AND id=?",task.getId(),ControlHistoryStore.tenant(),id);
                    audit.record(((Number)command.get("actor_id")).longValue(),ControlHistoryStore.tenant(),(String)command.get("session_id"),"ai-control-command.activate",id,"RUNNING","{}",null);return null;
                }));
            }
        }catch(RuntimeException failure){
            if(failure.getMessage()!=null && (failure.getMessage().startsWith("ENGINE_BUSY") || failure.getMessage().startsWith("ENGINE_FENCED")))return false;
            if(failure instanceof org.springframework.dao.TransientDataAccessException)return true;
            final boolean claimedThisTurn=preparationStarted;
            try{store.locked(symbol,()->{
                // An expired/changed old receipt can fail before claim; only a successful claim pins this turn's generation.
                if(claimedThisTurn && !Objects.equals(command.get("writer_generation"),store.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",Long.class,ControlHistoryStore.tenant(),symbol)))return null;
                String code=failure instanceof BalancedControlPlan.Failure?((BalancedControlPlan.Failure)failure).code:"COMMAND_FAILED";
                store.db.update("UPDATE market_control_command SET state='FAILED',prepared_json=NULL,error_code=?,message=? WHERE tenant_id=? AND id=? AND state IN ('ACCEPTED','PREPARING','READY')",code,"启动未完成："+failure.getClass().getSimpleName(),ControlHistoryStore.tenant(),id);return null;
            });}catch(RuntimeException fenced){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Command result remains recoverable: {}",id);return false;}
        }
        return preparationStarted;
    }
    private void validate(Map<String,Object> command,long symbol){
        TradingSymbol config=market.commandConfig(symbol);
        if(!Boolean.TRUE.equals(config.getIsEnabled()) || !Objects.equals(((Number)command.get("config_revision")).longValue(),config.getRowVersion()) || !Objects.equals(store.decode((String)command.get("parameters_json")).get("configBasis"),configBasis(config)) || ((Number)command.get("expires_at")).longValue()<=store.runtime.clock() || ((Number)command.get("control_revision")).longValue()!=store.runtime.revision(symbol))throw new BalancedControlPlan.Failure("START_BASIS_CHANGED","准备依据、版本或期限变化");
        if(store.db.queryForObject("SELECT COUNT(*) FROM tenant WHERE id=? AND status='ACTIVE' AND config_ready=1",Integer.class,ControlHistoryStore.tenant())!=1)throw new BusinessException("租户当前不可启动行情控制");
    }
    private void validateClaim(Map<String,Object> command,long symbol){
        Map<String,Object> current=rows(symbol,(String)command.get("request_key")).get(0);validate(current,symbol);
        if(!Arrays.asList("PREPARING","READY").contains(current.get("state")) || !store.runtime.owner.equals(current.get("owner_id")) || !Objects.equals(current.get("writer_generation"),command.get("writer_generation")))throw new BalancedControlPlan.Failure("START_BASIS_CHANGED","准备代次已取消");
    }
    private String configBasis(TradingSymbol config){
        return OrderRequest.hash(PriceControlPath.precision(config),ForexQuoteMarketService.marketCode(config),config.getMarketSource(),config.getSourceCategory(),config.getMarketInstrumentKey(),config.getBaseCurrency(),config.getQuoteCurrency(),config.getRandomMarketEnabled(),config.getRandomMarketStartedAt(),config.getRandomMarketBasePrice());
    }
    public Map<String,Object> metrics(){Map<String,Object> value=store.budget.metrics();value.put("queuedCommands",store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=? AND state IN ('ACCEPTED','PREPARING','READY')",Integer.class,ControlHistoryStore.tenant()));value.put("schedulerQueue",workers.getQueue().size());value.put("schedulerRejections",rejected.get());return value;}
}
