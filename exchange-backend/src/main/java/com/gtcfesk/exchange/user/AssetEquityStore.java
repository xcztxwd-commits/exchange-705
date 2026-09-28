package com.gtcfesk.exchange.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

@Repository @RequiredArgsConstructor
public class AssetEquityStore {
    private final DataSource dataSource;
    private final ObjectMapper json;
    public static final String[] TABLES={"asset_history_1m","asset_history_1h","asset_history_4h","asset_history_1d"};
    public static final long[] INTERVALS={60000,3600000,14400000,86400000};
    private static final String BASIS=EquityValuationService.BASIS;
    public static class Session {
        public final JdbcTemplate db;
        private final Connection connection;
        Session(Connection c) {connection=c;db=new JdbcTemplate(new SingleConnectionDataSource(c,true));}
        public void commit() {try{connection.commit();}catch(SQLException e){throw new IllegalStateException(e);}}
    }
    /** GET_LOCK, protected reads/writes, COMMIT and RELEASE_LOCK use this exact physical connection. */
    public void locked(String task,Consumer<Session> work) {
        try(Connection c=dataSource.getConnection()) {
            boolean auto=c.getAutoCommit(); int isolation=c.getTransactionIsolation(); boolean locked=false;
            Session s=new Session(c); String name="equity_v1_"+task;
            try {
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
                locked=Integer.valueOf(1).equals(s.db.queryForObject("select get_lock(?,0)",Integer.class,name));
                if(!locked) return;
                work.accept(s);c.commit();
            } catch(RuntimeException|SQLException e) {c.rollback();throw e;}
            finally {
                try {if(locked && !Integer.valueOf(1).equals(s.db.queryForObject("select release_lock(?)",Integer.class,name))) throw new IllegalStateException("Equity task lock lost");}
                finally {c.setAutoCommit(auto);c.setTransactionIsolation(isolation);}
            }
        } catch(SQLException e) {throw new IllegalStateException("Equity database transaction failed",e);}
    }
    public <T> T read(java.util.function.Function<JdbcTemplate,T> work) {
        try(Connection c=dataSource.getConnection()) {
            boolean auto=c.getAutoCommit(),readOnly=c.isReadOnly();int isolation=c.getTransactionIsolation();
            try {c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
                T result=work.apply(new JdbcTemplate(new SingleConnectionDataSource(c,true)));c.commit();return result;
            } catch(RuntimeException|SQLException e){c.rollback();throw e;}
            finally{c.setReadOnly(readOnly);c.setAutoCommit(auto);c.setTransactionIsolation(isolation);}
        }catch(SQLException e){throw new IllegalStateException(e);}
    }
    String json(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException("Invalid valuation evidence",e);}}
    public void saveMinutes(JdbcTemplate db,EquityValuationService.Batch batch,Collection<EquityValuationService.Value> values) {
        saveMinutes(db,batch,values,null);
    }
    public void saveMinutes(JdbcTemplate db,EquityValuationService.Batch batch,Collection<EquityValuationService.Value> values,Long closingBoundary) {
        if(closingBoundary!=null && (closingBoundary%60000!=0 || values.stream().anyMatch(v->!AssetEquityJobs.withinCaptureMinute(closingBoundary,v.observedAt))))
            throw new IllegalArgumentException("Observation outside scheduled closing minute");
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("quotes",batch.quotes);evidence.put("rates",batch.rates);
        db.update("insert into asset_history_quote_batch(batch_id,prepared_at,evidence) values(?,?,?)",batch.id,batch.preparedAt,json(evidence));
        for(EquityValuationService.Value v:values) {
            long bucket=closingBoundary==null?AssetHistoryBucket.floor(v.observedAt,60000):closingBoundary-60000;
            List<Object> args=new ArrayList<>(Arrays.asList(v.userId,BASIS,bucket,v.observedAt));
            args.addAll(v.amounts.values());args.add(v.status());args.add(String.join(",",v.reasons));args.add(batch.id);args.add(json(v.evidence));args.add(v.observedAt);
            String fields=String.join(",",v.amounts.keySet());
            db.update("insert into asset_history_1m(user_id,basis_version,bucket_start,observed_at,"+fields+",valuation_status,reason_code,quote_batch_id,valuation_evidence,created_at) values("+
                    String.join(",",Collections.nCopies(args.size(),"?"))+") on duplicate key update user_id=user_id",args.toArray());
            // Read the fixed observation, not a later duplicate attempt, when establishing the baseline.
            db.update("insert into asset_history_baseline(user_id,basis_version,capture_from,first_positive,first_positive_at) " +
                    "select user_id,basis_version,observed_at,case when net_equity>0 then net_equity end,case when net_equity>0 then observed_at end from asset_history_1m where user_id=? and basis_version=? and bucket_start=? and origin='OBSERVED' " +
                    "on duplicate key update capture_from=least(capture_from,values(capture_from)),first_positive=if(values(first_positive_at) is not null and (first_positive_at is null or values(first_positive_at)<first_positive_at),values(first_positive),first_positive),"+
                    "first_positive_at=if(values(first_positive_at) is not null and (first_positive_at is null or values(first_positive_at)<first_positive_at),values(first_positive_at),first_positive_at)",v.userId,BASIS,bucket);
        }
    }
    public long[] state(JdbcTemplate db,String task,long initial) {
        List<long[]> rows=db.query("select watermark,user_cursor from asset_history_job_state where task_name=? and basis_version=?",(rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},task,BASIS);
        return rows.isEmpty()?new long[]{initial,0}:rows.get(0);
    }
    public void progress(JdbcTemplate db,String task,long watermark,long cursor,long now) {
        db.update("insert into asset_history_job_state(task_name,basis_version,watermark,user_cursor,success_at) values(?,?,?,?,?) on duplicate key update watermark=values(watermark),user_cursor=values(user_cursor),success_at=values(success_at),error=null",task,BASIS,watermark,cursor,now);
    }
    public void error(String task,Exception failure) {
        locked("error_"+task,s->s.db.update("insert into asset_history_job_state(task_name,basis_version,watermark,user_cursor,error) values(?,?,0,0,?) on duplicate key update error=values(error)",
                "error_"+task,BASIS,failure.getClass().getSimpleName()));
    }
    public void saveBucket(JdbcTemplate db,int level,AssetHistoryBucket b,long now) {
        if(level<1 || level>3)throw new IllegalArgumentException("Invalid level");
        String fields="bucket_end,open_value,high_value,low_value,close_value,open_at,high_at,low_at,close_at,source_count,valid_sample_count,invalid_sample_count,expected_sample_count,quality,source_through,updated_at,finalized";
        List<String> updates=new ArrayList<>();
        for(String field:fields.split(","))updates.add(field+"=if(finalized=0 or values(finalized)=1,values("+field+"),"+field+")");
        db.update("insert into "+TABLES[level]+"(user_id,basis_version,bucket_start,"+fields+") values("+String.join(",",Collections.nCopies(20,"?"))+") on duplicate key update "+String.join(",",updates),
                b.userId,BASIS,b.start,b.end,b.open,b.high,b.low,b.close,b.openAt,b.highAt,b.lowAt,b.closeAt,b.sourceCount,b.valid,b.invalid,b.expected,b.quality(),b.through,now,b.finalized);
    }
    public List<AssetHistoryBucket> source(JdbcTemplate db,int level,List<Long> ids,long start,long end,long asOf,boolean finalized) {
        if(level<0 || level>3)throw new IllegalArgumentException("Invalid level");
        return db.query("select * from "+TABLES[level]+" where basis_version=? and bucket_start>=? and bucket_start<? and user_id in ("+EquityValuationService.placeholders(ids)+")"+
                (level==0?" and coalesce(effective_at,observed_at)<=?": " and source_through<=?"+(finalized?" and finalized=1":""))+" order by user_id,bucket_start",(rs,n)->level==0?AssetHistoryBucket.minute(rs):AssetHistoryBucket.row(rs),
                arguments(ids,start,end,asOf));
    }
    private Object[] arguments(List<Long> ids,long start,long end,long asOf) {
        List<Object> args=new ArrayList<>(Arrays.asList(BASIS,start,end));args.addAll(ids);args.add(asOf);return args.toArray();
    }
    /** Primary query uses requested table. Only intersected edge buckets recurse into bounded children. */
    public List<AssetHistoryBucket> window(JdbcTemplate db,int level,long user,long from,long to,long asOf) {
        long size=INTERVALS[level],first=AssetHistoryBucket.floor(from,size),last=AssetHistoryBucket.floor(to-1,size);
        List<AssetHistoryBucket> rows=source(db,level,Collections.singletonList(user),first,last+size,asOf,true);
        if(level==0) {rows.removeIf(b->b.through<from || b.through>=to);return rows;}
        Map<Long,AssetHistoryBucket> result=new TreeMap<>();rows.stream().filter(b->b.end<=asOf).forEach(b->result.put(b.start,b));
        Set<Long> edges=new LinkedHashSet<>();if(first<from)edges.add(first);if(last+size>to)edges.add(last);
        for(long edge:edges) {
            // Never synthesize an unfinished or not-yet-finalized parent from finer records.
            if(!result.containsKey(edge))continue;
            long start=Math.max(edge,from),end=Math.min(edge+size,to);
            AssetHistoryBucket clipped=new AssetHistoryBucket(user,start,end);
            for(AssetHistoryBucket child:window(db,level-1,user,start,end,asOf))clipped.merge(child);
            result.remove(edge);if(clipped.sourceCount>0)result.put(edge,clipped);
        }
        return new ArrayList<>(result.values());
    }
}
