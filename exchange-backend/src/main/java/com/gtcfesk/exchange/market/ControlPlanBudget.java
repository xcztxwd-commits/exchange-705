package com.gtcfesk.exchange.market;
import com.gtcfesk.exchange.common.BusinessException;
import java.util.*;
/** Conservative live heap accounting (256 bytes/point), not a hard tenant Cell. */
final class ControlPlanBudget {
    private final Map<Long,Long> used=new HashMap<>();
    private long total,rejected;
    synchronized Lease acquire(long bytes) {
        long tenant=ControlHistoryStore.tenant();
        if(bytes<0 || bytes>32L*1024*1024 || used.getOrDefault(tenant,0L)+bytes>32L*1024*1024 || total+bytes>64L*1024*1024) {
            rejected++;throw new BusinessException("PLAN_BUDGET_EXHAUSTED: 计划内存预算不足");
        }
        used.merge(tenant,bytes,Long::sum);total+=bytes;return new Lease(tenant,bytes);
    }
    synchronized Map<String,Object> metrics(){Map<String,Object> result=new LinkedHashMap<>();result.put("activePlanBytes",used.getOrDefault(ControlHistoryStore.tenant(),0L));result.put("globalPlanBytes",total);result.put("planBudgetRejections",rejected);return result;}
    final class Lease implements AutoCloseable {
        final long tenant,bytes;boolean closed;
        Lease(long tenant,long bytes){this.tenant=tenant;this.bytes=bytes;}
        public void close(){synchronized(ControlPlanBudget.this){if(!closed){closed=true;total-=bytes;long remaining=used.get(tenant)-bytes;if(remaining==0)used.remove(tenant);else used.put(tenant,remaining);}}}
    }
}
