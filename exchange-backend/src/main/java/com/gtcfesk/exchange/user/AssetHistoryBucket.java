package com.gtcfesk.exchange.user;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** One OHLC reducer for minutes, all rollup levels and clipped boundary buckets. */
public class AssetHistoryBucket {
    public long userId, start, end, sourceCount, valid, invalid, expected, through;
    public Long openAt, highAt, lowAt, closeAt;
    public BigDecimal open, high, low, close;
    public boolean finalized;
    public static long floor(long time,long interval) {return Math.floorDiv(time,interval)*interval;}
    public AssetHistoryBucket(long user,long start,long end) {
        this.userId=user; this.start=start; this.end=end; this.expected=(end-start+59999)/60000;
    }
    public void merge(AssetHistoryBucket child) {
        sourceCount++; valid+=child.valid; invalid+=child.invalid; through=Math.max(through,child.through);
        if(child.open==null) return;
        if(openAt==null || child.openAt<openAt) {open=child.open;openAt=child.openAt;}
        if(closeAt==null || child.closeAt>closeAt) {close=child.close;closeAt=child.closeAt;}
        if(high==null || child.high.compareTo(high)>0 || child.high.compareTo(high)==0 && child.highAt<highAt) {high=child.high;highAt=child.highAt;}
        if(low==null || child.low.compareTo(low)<0 || child.low.compareTo(low)==0 && child.lowAt<lowAt) {low=child.low;lowAt=child.lowAt;}
    }
    public String quality() {return valid==expected && invalid==0?"COMPLETE":"PARTIAL";}
    public static AssetHistoryBucket minute(ResultSet rs) throws SQLException {
        Long effective=nullableLong(rs,"effective_at");
        long time=effective==null?rs.getLong("observed_at"):effective,start=rs.getLong("bucket_start");
        AssetHistoryBucket b=new AssetHistoryBucket(rs.getLong("user_id"),start,start+60000);
        b.open=b.high=b.low=b.close=rs.getBigDecimal("net_equity");
        b.sourceCount=1; b.through=time;
        if(b.close==null) b.invalid=1;
        else {b.valid=1;b.openAt=b.highAt=b.lowAt=b.closeAt=time;}
        b.finalized=true; return b;
    }
    static Long nullableLong(ResultSet rs,String name) throws SQLException {long v=rs.getLong(name);return rs.wasNull()?null:v;}
    public static AssetHistoryBucket row(ResultSet rs) throws SQLException {
        AssetHistoryBucket b=new AssetHistoryBucket(rs.getLong("user_id"),rs.getLong("bucket_start"),rs.getLong("bucket_end"));
        b.open=rs.getBigDecimal("open_value");b.high=rs.getBigDecimal("high_value");b.low=rs.getBigDecimal("low_value");b.close=rs.getBigDecimal("close_value");
        b.openAt=nullableLong(rs,"open_at");b.highAt=nullableLong(rs,"high_at");b.lowAt=nullableLong(rs,"low_at");b.closeAt=nullableLong(rs,"close_at");
        b.sourceCount=rs.getLong("source_count");b.valid=rs.getLong("valid_sample_count");b.invalid=rs.getLong("invalid_sample_count");b.expected=rs.getLong("expected_sample_count");
        b.finalized=rs.getBoolean("finalized");b.through=rs.getLong("source_through");return b;
    }
    static String decimal(BigDecimal v){return v==null?null:v.toPlainString();}
    public Map<String,Object> api() {
        Map<String,Object> p=new LinkedHashMap<>();
        p.put("time",closeAt==null?Math.max(start,through):closeAt); p.put("value",decimal(close));
        p.put("bucketStart",start);p.put("bucketEnd",end);
        p.put("open",decimal(open));p.put("high",decimal(high));p.put("low",decimal(low));p.put("close",decimal(close));
        p.put("openAt",openAt);p.put("highAt",highAt);p.put("lowAt",lowAt);p.put("closeAt",closeAt);
        p.put("sourceCount",sourceCount);p.put("validSampleCount",valid);p.put("invalidSampleCount",invalid);p.put("expectedSampleCount",expected);
        p.put("finalized",finalized);p.put("quality",quality());p.put("sourceThrough",through);return p;
    }
}
