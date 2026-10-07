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
    private final Set<String> fencedCommands=ConcurrentHashMap.newKeySet();
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
        if(target==null || target.signum()<=0 || target.compareTo(BigDecimal.TEN.pow(16))>=0)throw new BusinessException("控盘参数或品种状态无效");
        Map<String,Object> parameters=new TreeMap<>(json.convertValue(options==null?new TargetControlOptions():options,Map.class));
        parameters.put("duration",duration);parameters.put("target",target.stripTrailingZeros().toPlainString());parameters.put("intensity",intensity);parameters.put("oscillation",oscillation);
        parameters.remove("durationSeconds");parameters.remove("targetPrice");parameters.remove("randomOscillation");parameters.remove("requestKey");
        parameters.replaceAll((name,value)->value instanceof BigDecimal?((BigDecimal)value).stripTrailingZeros().toPlainString():value);
        // Preserve hashes of already accepted START requests across this additive rollout.
        String hash=OrderRequest.hash(parameters);parameters.put("action","START");
        return acceptCommand(symbol,duration,intensity,requestKey,parameters,hash);
    }
    public Map<String,Object> acceptRestore(long symbol,int duration,int intensity,boolean oscillation,String requestKey){
        Map<String,Object> parameters=new TreeMap<>();parameters.put("action","RESTORE");parameters.put("duration",duration);parameters.put("intensity",intensity);parameters.put("oscillation",oscillation);
        return acceptCommand(symbol,duration,intensity,requestKey,parameters,OrderRequest.hash(parameters));
    }
    private void queueLock(){
        store.db.update("INSERT INTO market_engine_tenant(tenant_id) VALUES(?) ON DUPLICATE KEY UPDATE tenant_id=VALUES(tenant_id)",ControlHistoryStore.tenant());
        store.db.queryForObject("SELECT tenant_id FROM market_engine_tenant WHERE tenant_id=? FOR UPDATE",Long.class,ControlHistoryStore.tenant());
    }
    private Map<String,Object> acceptCommand(long symbol,int duration,int intensity,String requestKey,Map<String,Object> parameters,String hash){
        String key=OrderRequest.required(requestKey);market.commandConfig(symbol);ControlIdentity actor=operator();
        if(duration<1 || duration>86400 || intensity<1 || intensity>10)throw new BusinessException("控盘参数或品种状态无效");
        long started=System.nanoTime();
        Map<String,Object> accepted=store.transaction(()->{
            queueLock();List<Map<String,Object>> original=rows(symbol,key);
            if(!original.isEmpty()){
                Map<String,Object> row=original.get(0);
                if(!"CANCEL".equals(action(row)))OrderRequest.same((String)row.get("parameter_hash"),hash);
                return receipt(row);
            }
            if(taskKeyExists(symbol,key))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"LEGACY_REQUEST_KEY_REQUIRES_RECONCILIATION");
            if(store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=? AND state IN ('ACCEPTED','PREPARING','READY')",Integer.class,ControlHistoryStore.tenant())>=32){rejected.incrementAndGet();throw new BusinessException("COMMAND_QUEUE_FULL: 租户准备队列已满");}
            // Queue acceptance has no runtime writer lock, plan generation, sampling, or lease acquisition.
            TradingSymbol config=market.commandConfig(symbol);
            if(!Boolean.TRUE.equals(config.getIsEnabled()) || parameters.containsKey("target") && new BigDecimal((String)parameters.get("target")).stripTrailingZeros().scale()>PriceControlPath.precision(config))throw new BusinessException("控盘参数或品种状态无效");
            if(store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=? AND symbol_id=? AND state IN ('ACCEPTED','PREPARING','READY')",Integer.class,ControlHistoryStore.tenant(),symbol)>0)throw new BusinessException("该品种已有准备中的控制命令");
            parameters.put("configBasis",configBasis(config));
            if("START".equals(parameters.get("action")) && parameters.get("stepFormula")==null)parameters.put("stepFormula",market.controlFormula(symbol).get("stepFormula"));
            long now=store.runtime.clock();String id=UUID.randomUUID().toString();
            store.db.update("INSERT INTO market_control_command(tenant_id,id,symbol_id,request_key,parameter_hash,parameters_json,state,actor_id,session_id,config_revision,control_revision,seed,accepted_at,expires_at) VALUES(?,?,?,?,?,?,'ACCEPTED',?,?,?,?,?,?,?)",ControlHistoryStore.tenant(),id,symbol,key,hash,store.encode(parameters),actor.getActorId(),actor.getAccessSessionId(),config.getRowVersion(),store.runtime.revision(symbol),new java.security.SecureRandom().nextLong(),now,now+300000);
            audit.record(actor.getActorId(),actor.getTenantId(),actor.getAccessSessionId(),"ai-control-command.accept",id,"ACCEPTED","{}",null);
            return receipt(rows(symbol,key).get(0));
        });
        org.slf4j.MDC.put("commandId",String.valueOf(accepted.get("commandId")));
        if(accepted.get("taskId")!=null)org.slf4j.MDC.put("taskId",String.valueOf(accepted.get("taskId")));
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Control command phase=accept traceId={} action={} tenant={} symbol={} requestKey={} commandId={} taskId={} state={} durationMs={}",org.slf4j.MDC.get("traceId"),accepted.get("action"),ControlHistoryStore.tenant(),symbol,key,accepted.get("commandId"),accepted.get("taskId"),accepted.get("state"),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        return accepted;
    }
    static ControlIdentity operator(){
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
    private Map<String,Object> commandLocked(long symbol,String key){return store.db.queryForMap("SELECT * FROM market_control_command WHERE tenant_id=? AND symbol_id=? AND request_key=? FOR UPDATE",ControlHistoryStore.tenant(),symbol,key);}
    private boolean taskKeyExists(long symbol,String key){return store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=? AND symbol_id=? AND request_key=?",Integer.class,ControlHistoryStore.tenant(),symbol,key)>0;}
    private String action(Map<String,Object> row){Object action=store.decode((String)row.get("parameters_json")).get("action");return action==null?"START":String.valueOf(action);}
    public Map<String,Object> query(long symbol,String key){market.commandConfig(symbol);List<Map<String,Object>> result=rows(symbol,OrderRequest.required(key));if(result.isEmpty())throw new BusinessException("控制命令不存在");return receipt(result.get(0));}
    private Map<String,Object> receipt(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();result.put("commandId",row.get("id"));result.put("requestKey",row.get("request_key"));result.put("symbolId",row.get("symbol_id"));result.put("action",action(row));result.put("state",row.get("state"));result.put("errorCode",row.get("error_code"));result.put("message",ControlHistoryStore.decodeCommandMessage((String)row.get("message")));result.put("taskId",row.get("task_id"));result.put("acceptedAt",row.get("accepted_at"));result.put("retryCount",row.get("retry_count"));result.put("retryAt",row.get("retry_at"));return result;}
    public Map<String,Object> cancel(long symbol,String key){
        TradingSymbol config=market.commandConfig(symbol);ControlIdentity actor=operator();String originalKey=key==null?null:OrderRequest.required(key);
        Map<String,Object> cancelled=store.transaction(()->{
            queueLock();
            if(originalKey!=null && rows(symbol,originalKey).isEmpty()){
                long now=store.runtime.clock();String id=UUID.randomUUID().toString();
                store.db.update("INSERT INTO market_control_command(tenant_id,id,symbol_id,request_key,parameter_hash,parameters_json,state,actor_id,session_id,config_revision,control_revision,seed,accepted_at,expires_at,error_code,message) VALUES(?,?,?,?,?,?,'CANCELLED',?,?,?,?,0,?,?,?,?)",ControlHistoryStore.tenant(),id,symbol,originalKey,OrderRequest.hash("CANCEL",originalKey),store.encode(Collections.singletonMap("action","CANCEL")),actor.getActorId(),actor.getAccessSessionId(),config.getRowVersion(),store.runtime.revision(symbol),now,now,"CONTROL_CANCELLED",ControlHistoryStore.encodeCommandMessage("控制请求已取消"));
            }
            store.db.update("UPDATE market_control_command SET state='CANCELLED',prepared_json=NULL,error_code='CONTROL_CANCELLED',message=? WHERE tenant_id=? AND symbol_id=? AND state IN ('ACCEPTED','PREPARING','READY')"+(originalKey==null?"":" AND request_key=?"),originalKey==null?new Object[]{ControlHistoryStore.encodeCommandMessage("控制准备已取消"),ControlHistoryStore.tenant(),symbol}:new Object[]{ControlHistoryStore.encodeCommandMessage("控制准备已取消"),ControlHistoryStore.tenant(),symbol,originalKey});
            Map<String,Object> result=originalKey==null?Collections.emptyMap():receipt(commandLocked(symbol,originalKey));
            audit.record(actor.getActorId(),actor.getTenantId(),actor.getAccessSessionId(),"ai-control-command.cancel",originalKey==null?String.valueOf(symbol):String.valueOf(result.get("commandId")),String.valueOf(result.getOrDefault("state","CANCELLED")),"{}",null);
            return result;
        });
        if(cancelled.get("commandId")!=null)org.slf4j.MDC.put("commandId",String.valueOf(cancelled.get("commandId")));
        if(cancelled.get("taskId")!=null)org.slf4j.MDC.put("taskId",String.valueOf(cancelled.get("taskId")));
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Control command phase=cancel-committed traceId={} action={} tenant={} symbol={} requestKey={} commandId={} taskId={} state={}",org.slf4j.MDC.get("traceId"),cancelled.get("action"),ControlHistoryStore.tenant(),symbol,originalKey,cancelled.get("commandId"),cancelled.get("taskId"),cancelled.get("state"));
        return cancelled;
    }
    public Map<String,Object> stopControl(long symbol,String key){
        Map<String,Object> cancelled=cancel(symbol,key);
        // An unknown/pending request has no active task to stop. Its tombstone must not wait on a bad engine.
        if("CANCELLED".equals(cancelled.get("state")))return cancelled;
        return store.locked(symbol,()->{store.runtime.invalidate(symbol);return market.stopControl(symbol);});
    }
    public Map<String,Object> manualControl(long symbol,boolean enabled,BigDecimal offset,String key){
        if(key!=null)cancel(symbol,key);
        return market.manualControl(symbol,enabled,offset);
    }
    void runOne(){
        List<Map<String,Object>> pending=store.db.queryForList("SELECT * FROM market_control_command WHERE tenant_id=? AND state IN ('ACCEPTED','PREPARING','READY') AND retry_at<=? ORDER BY accepted_at,id LIMIT 32",ControlHistoryStore.tenant(),store.runtime.clock());
        // A busy/fenced symbol must not starve the rest of this tenant's bounded queue.
        for(Map<String,Object> command:pending)if(!fencedCommands.contains(command.get("id")) && runCommand(command))return;
    }
    private boolean runCommand(Map<String,Object> command){
        long symbol=((Number)command.get("symbol_id")).longValue();String id=(String)command.get("id");boolean preparationStarted=false;
        final Long[] claimedGeneration={null};
        try{
            boolean claimed=store.locked(symbol,()->{
                Map<String,Object> current=commandLocked(symbol,(String)command.get("request_key"));
                if(!Arrays.asList("ACCEPTED","PREPARING","READY").contains(current.get("state")))return false;
                validate(current,symbol);
                long generation=store.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",Long.class,ControlHistoryStore.tenant(),symbol);
                // A new process resumes from the persisted seed, never accepts a late old Prepared.
                claimedGeneration[0]=generation;
                store.db.update("UPDATE market_control_command SET state='PREPARING',owner_id=?,writer_generation=? WHERE tenant_id=? AND id=?",store.runtime.owner,generation,ControlHistoryStore.tenant(),id);
                return true;
            });
            if(!claimed)return false;preparationStarted=true;
            // Do not publish a generation captured by a rolled-back or unacknowledged claim.
            command.put("writer_generation",claimedGeneration[0]);
            Map<String,Object> p=store.decode((String)command.get("parameters_json"));
            if("RESTORE".equals(action(command))){
                store.locked(symbol,()->{
                    validateClaim(command,symbol);
                    market.restoreControl(symbol,((Number)p.get("duration")).intValue(),((Number)p.get("intensity")).intValue(),Boolean.TRUE.equals(p.get("oscillation")),(String)command.get("request_key"));
                    activated(command,symbol);return null;
                });return true;
            }
            TargetControlOptions options=json.convertValue(p,TargetControlOptions.class);
            // Unknown extra persisted fields are excluded from bean conversion.
            int duration=((Number)p.get("duration")).intValue(),intensity=((Number)p.get("intensity")).intValue();BigDecimal target=new BigDecimal((String)p.get("target"));boolean oscillation=Boolean.TRUE.equals(p.get("oscillation"));
            try(PersistentPriceControl.Prepared prepared=market.prepareCommand(symbol,duration,target,intensity,oscillation,options,((Number)command.get("seed")).longValue())){
                Map<String,Object> ready=new LinkedHashMap<>();ready.put("parameters_json",prepared.encoded.parameters);ready.put("prices_json",prepared.encoded.prices);ready.put("summary_json",prepared.encoded.summary);ready.put("checksum",prepared.encoded.checksum);ready.put("seed",prepared.seed);ready.put("previousTaskId",prepared.previousTaskId);ready.put("start",prepared.start.toPlainString());
                store.locked(symbol,()->{validateClaim(command,symbol);store.db.update("UPDATE market_control_command SET state='READY',prepared_json=? WHERE tenant_id=? AND id=?",store.encode(ready),ControlHistoryStore.tenant(),id);return null;});
                store.withPlans(Collections.singletonList(symbol),()->store.locked(symbol,()->{
                    validateClaim(command,symbol);
                    market.activateCommand(symbol,duration,target,intensity,oscillation,(String)command.get("request_key"),options,prepared);
                    activated(command,symbol);return null;
                }));
            }
        }catch(RuntimeException failure){
            String normalized=MarketEngineFailure.normalize(failure);
            if(Arrays.asList("ENGINE_BUSY","ENGINE_FENCED","ENGINE_BUDGET","ENGINE_TRANSIENT").contains(normalized)){
                if("ENGINE_FENCED".equals(normalized) && "AUTHORITY_LOST".equals(store.runtime.fenceReason(symbol))){
                    fencedCommands.add(id);
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Control command phase=fence traceId={} action={} tenant={} symbol={} requestKey={} commandId={} reason=AUTHORITY_LOST generation={}",org.slf4j.MDC.get("traceId"),action(command),ControlHistoryStore.tenant(),symbol,command.get("request_key"),id,command.get("writer_generation"));return false;
                }
                defer(command,symbol,normalized,claimedGeneration[0]);return false;
            }
            final boolean claimedThisTurn=preparationStarted;
            try{store.locked(symbol,()->{
                // An expired/changed old receipt can fail before claim; only a successful claim pins this turn's generation.
                if(claimedThisTurn && !Objects.equals(command.get("writer_generation"),store.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",Long.class,ControlHistoryStore.tenant(),symbol)))return null;
                String code=failure instanceof BalancedControlPlan.Failure?((BalancedControlPlan.Failure)failure).code:"COMMAND_FAILED";
                // Only bounded validation messages are user-facing; unexpected errors remain opaque.
                String message=Arrays.asList("AMPLITUDE_PRECISION_UNREPRESENTABLE","TARGET_AMPLITUDE_INFEASIBLE","CORRIDOR_PRECISION_UNREPRESENTABLE","CORRIDOR_STEP_INFEASIBLE","PLAN_SEARCH_EXHAUSTED","PLAN_COMPUTE_BUSY","INVALID_PARAMETERS","INVALID_FORMULA","ALGORITHM_DISABLED","START_BASIS_CHANGED","LEGACY_REQUEST_KEY_REQUIRES_RECONCILIATION").contains(code)
                    ?failure.getMessage():"启动未完成："+failure.getClass().getSimpleName();
                store.db.update("UPDATE market_control_command SET state='FAILED',prepared_json=NULL,error_code=?,message=? WHERE tenant_id=? AND id=? AND state IN ('ACCEPTED','PREPARING','READY')",code,ControlHistoryStore.encodeCommandMessage(message),ControlHistoryStore.tenant(),id);return null;
            });}catch(RuntimeException fenced){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Command result remains recoverable: {}",id);return false;}
        }
        return preparationStarted;
    }
    private void activated(Map<String,Object> command,long symbol){
        PersistentPriceControl.Task task=new PersistentPriceControl(store).latest(symbol);
        store.db.update("UPDATE market_control_command SET state='RUNNING',task_id=?,prepared_json=NULL,error_code=NULL,message=NULL,retry_at=0 WHERE tenant_id=? AND id=?",task==null?null:task.getId(),ControlHistoryStore.tenant(),command.get("id"));
        final long tenant=ControlHistoryStore.tenant();final String taskId=task==null?null:task.getId();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void afterCommit(){org.slf4j.LoggerFactory.getLogger(MarketControlCommands.class).info("Control command phase=committed traceId={} action={} tenant={} symbol={} requestKey={} commandId={} taskId={} state=RUNNING",org.slf4j.MDC.get("traceId"),action(command),tenant,symbol,command.get("request_key"),command.get("id"),taskId);}
        });
        audit.record(((Number)command.get("actor_id")).longValue(),tenant,(String)command.get("session_id"),"ai-control-command.activate",(String)command.get("id"),"RUNNING","{}",null);
    }
    private void defer(Map<String,Object> command,long symbol,String reason,Long attemptedGeneration){
        Long generation=attemptedGeneration!=null?attemptedGeneration:command.get("writer_generation")==null?null:((Number)command.get("writer_generation")).longValue();
        try{store.transaction(()->{
            // Keep runtime-before-command lock order; an uncertain claim commit is reconciled by current reads.
            List<Map<String,Object>> authority=generation==null?Collections.emptyList():store.db.queryForList("SELECT owner_id,writer_generation FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=? FOR UPDATE",ControlHistoryStore.tenant(),symbol);
            Map<String,Object> current=commandLocked(symbol,(String)command.get("request_key"));
            if(!Arrays.asList("ACCEPTED","PREPARING","READY").contains(current.get("state")))return null;
            if("ACCEPTED".equals(current.get("state"))){
                if(current.get("owner_id")!=null || current.get("writer_generation")!=null)return null;
            }else if(generation==null || !store.runtime.owner.equals(current.get("owner_id")) || !Objects.equals(generation,current.get("writer_generation")) || authority.isEmpty() || !store.runtime.owner.equals(authority.get(0).get("owner_id")) || !Objects.equals(generation,authority.get(0).get("writer_generation")))return null;
            if(!"ACCEPTED".equals(current.get("state")))store.runtime.rememberCommittedGeneration(symbol,generation);
            int attempt=((Number)current.get("retry_count")).intValue()+1;boolean exhausted=attempt>=5;
            long delay=Math.min(4000L,250L<<(Math.min(4,attempt-1)))+Math.floorMod(Objects.hash(command.get("id"),attempt),125);
            store.db.update("UPDATE market_control_command SET state=?,retry_count=?,retry_at=?,error_code=?,message=?,prepared_json=CASE WHEN ? THEN NULL ELSE prepared_json END WHERE tenant_id=? AND id=?",exhausted?"FAILED":current.get("state"),attempt,store.runtime.clock()+delay,exhausted?"COMMAND_RETRY_EXHAUSTED":reason,ControlHistoryStore.encodeCommandMessage(exhausted?"控制命令有限重试已耗尽":"引擎暂不可用，等待有限退避"),exhausted,ControlHistoryStore.tenant(),command.get("id"));
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Control command phase=retry traceId={} action={} tenant={} symbol={} requestKey={} commandId={} reason={} attempt={} delayMs={} exhausted={}",org.slf4j.MDC.get("traceId"),action(current),ControlHistoryStore.tenant(),symbol,current.get("request_key"),current.get("id"),reason,attempt,delay,exhausted);return null;
        });}catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Command retry persistence failed command={} reason={}",command.get("id"),MarketEngineFailure.normalize(unavailable));}
    }
    private void validate(Map<String,Object> command,long symbol){
        if(!store.db.queryForList("SELECT id FROM market_control_task WHERE tenant_id=? AND symbol_id=? AND request_key=? LIMIT 1 FOR UPDATE",String.class,ControlHistoryStore.tenant(),symbol,command.get("request_key")).isEmpty())throw new BalancedControlPlan.Failure("LEGACY_REQUEST_KEY_REQUIRES_RECONCILIATION","旧同步请求键需要人工核对，请勿复用启动或恢复");
        TradingSymbol config=market.commandConfig(symbol);
        if(!Boolean.TRUE.equals(config.getIsEnabled()) || !Objects.equals(((Number)command.get("config_revision")).longValue(),config.getRowVersion()) || !Objects.equals(store.decode((String)command.get("parameters_json")).get("configBasis"),configBasis(config)) || ((Number)command.get("expires_at")).longValue()<=store.runtime.clock() || ((Number)command.get("control_revision")).longValue()!=store.runtime.revision(symbol))throw new BalancedControlPlan.Failure("START_BASIS_CHANGED","准备依据、版本或期限变化");
        if(store.db.queryForObject("SELECT COUNT(*) FROM tenant WHERE id=? AND status='ACTIVE' AND config_ready=1",Integer.class,ControlHistoryStore.tenant())!=1)throw new BusinessException("租户当前不可启动行情控制");
    }
    private void validateClaim(Map<String,Object> command,long symbol){
        Map<String,Object> current=commandLocked(symbol,(String)command.get("request_key"));validate(current,symbol);
        if(!Arrays.asList("PREPARING","READY").contains(current.get("state")) || !store.runtime.owner.equals(current.get("owner_id")) || !Objects.equals(current.get("writer_generation"),command.get("writer_generation")))throw new BalancedControlPlan.Failure("START_BASIS_CHANGED","准备代次已取消");
    }
    private String configBasis(TradingSymbol config){
        return OrderRequest.hash(PriceControlPath.precision(config),ForexQuoteMarketService.marketCode(config),config.getMarketSource(),config.getSourceCategory(),config.getMarketInstrumentKey(),config.getBaseCurrency(),config.getQuoteCurrency(),config.getRandomMarketEnabled(),config.getRandomMarketStartedAt(),config.getRandomMarketBasePrice());
    }
    public Map<String,Object> metrics(){Map<String,Object> value=store.budget.metrics();value.put("queuedCommands",store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=? AND state IN ('ACCEPTED','PREPARING','READY')",Integer.class,ControlHistoryStore.tenant()));value.put("schedulerQueue",workers.getQueue().size());value.put("schedulerRejections",rejected.get());return value;}
}
