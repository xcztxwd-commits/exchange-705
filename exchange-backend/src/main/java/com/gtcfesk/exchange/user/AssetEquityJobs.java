package com.gtcfesk.exchange.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent bounded single-worker channels; duplicate ticks coalesce, database locks fence replicas. */
@Slf4j @Service @RequiredArgsConstructor
public class AssetEquityJobs {
    private final AssetEquityStore store;
    private final EquityValuationService valuation;
    @Value("${asset.history.equity.collect-enabled:false}") private boolean enabled;
    private final ExecutorService collector=Executors.newSingleThreadExecutor(), rollup=Executors.newSingleThreadExecutor();
    private final AtomicBoolean collecting=new AtomicBoolean(),rolling=new AtomicBoolean();
    private static final String BASIS=EquityValuationService.BASIS;
    @Scheduled(cron="5 * * * * *",zone="UTC") public void minuteTick(){submit(collector,collecting,()->{capture();requestRollup();});}
    @Scheduled(cron="15 2 * * * *",zone="UTC") public void hourTick(){requestRollup();}
    @Scheduled(cron="30 4 0/4 * * *",zone="UTC") public void fourHourTick(){requestRollup();}
    @Scheduled(cron="45 8 0 * * *",zone="UTC") public void dayTick(){requestRollup();}
    @Scheduled(cron="0 */5 * * * *",zone="UTC") public void recoveryTick(){requestRollup();}
    void requestRollup(){submit(rollup,rolling,this::aggregate);}
    private void submit(ExecutorService executor,AtomicBoolean busy,Runnable work){
        if(!enabled || !busy.compareAndSet(false,true))return;
        executor.submit(()->{try{work.run();}catch(Exception e){
            log.error("Equity history task failed",e);
            try{store.error(executor==collector?"capture":"rollup",e);}catch(Exception recordFailure){log.error("Cannot record equity task failure",recordFailure);}
        }finally{busy.set(false);}});
    }
    @PreDestroy public void stop(){collector.shutdown();rollup.shutdown();}

    public void capture(){
        store.locked("capture",s->{
            long tick=AssetHistoryBucket.floor(System.currentTimeMillis(),60000);
            long[] progress=store.state(s.db,"capture",tick); long cursor=progress[0]==tick?progress[1]:0;
            s.commit();
            for(int batch=0;batch<1000;batch++) {
                List<Long> ids=s.db.query("select id from user_account where id>? order by id limit 100",(rs,n)->rs.getLong(1),cursor);
                s.commit();if(ids.isEmpty())break;
                EquityValuationService.Batch quotes=valuation.prepare(ids);
                long observed=System.currentTimeMillis();
                // Crossing a minute is a new observation, never a late backfill into the original minute.
                Map<Long,EquityValuationService.Value> values=valuation.read(s.db,ids,quotes,observed);
                store.saveMinutes(s.db,quotes,values.values()); cursor=ids.get(ids.size()-1);
                store.progress(s.db,"capture",tick,cursor,observed);s.commit();
                if(ids.size()<100 || System.currentTimeMillis()-observed>45000)break;
            }
        });
    }
    public void aggregate(){
        store.locked("rollup",s->{
            Long first=s.db.queryForObject("select min(bucket_start) from asset_history_1m where basis_version=?",Long.class,BASIS);
            s.commit();if(first==null)return;
            long now=System.currentTimeMillis();
            for(int level=1;level<=3;level++)finalizeLevel(s,level,first,now);
            for(int level=1;level<=3;level++)draftLevel(s,level,now);
        });
    }
    void finalizeLevel(AssetEquityStore.Session s,int level,long first,long now){
        long size=AssetEquityStore.INTERVALS[level],limit=AssetHistoryBucket.floor(now,size);
        if(level>1)limit=Math.min(limit,store.state(s.db,"rollup_"+(level-1),AssetHistoryBucket.floor(first,AssetEquityStore.INTERVALS[level-1]))[0]);
        long[] state=store.state(s.db,"rollup_"+level,AssetHistoryBucket.floor(first,size));s.commit();
        long start=state[0],cursor=state[1];
        // Bounded recovery continues at the persisted bucket/user cursor next tick, not an unbounded catch-up transaction.
        for(int batches=0;batches<48 && start+size<=limit;batches++){
            List<Long> ids=users(s.db,level-1,start,start+size,cursor);
            if(!ids.isEmpty())reduce(s.db,level,ids,start,start+size,now,true);
            if(ids.size()<100){start+=size;cursor=0;}else cursor=ids.get(ids.size()-1);
            store.progress(s.db,"rollup_"+level,start,cursor,now);s.commit();
        }
    }
    void draftLevel(AssetEquityStore.Session s,int level,long now){
        long size=AssetEquityStore.INTERVALS[level],start=AssetHistoryBucket.floor(now,size),cursor=0;
        for(int batches=0;batches<1000;batches++){
            List<Long> ids=users(s.db,level-1,start,start+size,cursor);if(ids.isEmpty()){s.commit();break;}
            reduce(s.db,level,ids,start,start+size,now,false);s.commit();
            if(ids.size()<100)break;cursor=ids.get(ids.size()-1);
        }
    }
    List<Long> users(JdbcTemplate db,int level,long start,long end,long cursor){
        return db.query("select distinct user_id from "+AssetEquityStore.TABLES[level]+" where basis_version=? and bucket_start>=? and bucket_start<? and user_id>? order by user_id limit 100",(rs,n)->rs.getLong(1),BASIS,start,end,cursor);
    }
    void reduce(JdbcTemplate db,int level,List<Long> ids,long start,long end,long now,boolean finalized){
        Map<Long,AssetHistoryBucket> buckets=new LinkedHashMap<>();ids.forEach(id->buckets.put(id,new AssetHistoryBucket(id,start,end)));
        for(AssetHistoryBucket child:store.source(db,level-1,ids,start,end,now,finalized))buckets.get(child.userId).merge(child);
        for(AssetHistoryBucket b:buckets.values()){b.finalized=finalized;store.saveBucket(db,level,b,now);}
    }
}
