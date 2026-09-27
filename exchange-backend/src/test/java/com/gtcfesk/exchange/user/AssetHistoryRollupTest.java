package com.gtcfesk.exchange.user;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssetHistoryRollupTest {
    static AssetHistoryBucket sample(long at,String n){AssetHistoryBucket b=new AssetHistoryBucket(1,at,at+60000);b.through=at;if(n==null)b.invalid=1;else{b.valid=1;b.open=b.high=b.low=b.close=new BigDecimal(n);b.openAt=b.highAt=b.lowAt=b.closeAt=at;}return b;}
    @Test void hierarchicalExtremaAndOriginalTimesEqualRawScan(){
        AssetHistoryBucket direct=new AssetHistoryBucket(1,0,86400000),day=new AssetHistoryBucket(1,0,86400000);
        for(int h4=0;h4<6;h4++){AssetHistoryBucket four=new AssetHistoryBucket(1,h4*14400000L,(h4+1)*14400000L);
            for(int h=0;h<4;h++){AssetHistoryBucket hour=new AssetHistoryBucket(1,four.start+h*3600000L,four.start+(h+1)*3600000L);
                for(int m=0;m<60;m++){long at=hour.start+m*60000;AssetHistoryBucket minute=sample(at,m==7?null:m==8?"999":m==9?"-100":String.valueOf(m));hour.merge(minute);direct.merge(minute);}
                four.merge(hour);
            }day.merge(four);
        }
        assertEquals(direct.high,day.high);assertEquals(direct.low,day.low);assertEquals(direct.open,day.open);assertEquals(direct.close,day.close);
        assertEquals(direct.highAt,day.highAt);assertEquals(direct.lowAt,day.lowAt);assertEquals(direct.closeAt,day.closeAt);
        assertEquals(24,day.invalid);assertEquals(1416,day.valid);assertEquals(6,day.sourceCount);assertEquals("PARTIAL",day.quality());
    }
    @Test void nullIsNotZeroAndNegativeHighIsReal(){AssetHistoryBucket b=new AssetHistoryBucket(1,0,180000);b.merge(sample(0,null));assertNull(b.close);b.merge(sample(60000,"-9"));b.merge(sample(120000,"-3"));assertEquals(new BigDecimal("-3"),b.high);assertEquals(new BigDecimal("-9"),b.low);}
    @Test void tiesUseEarliestExtremaAndCloseUsesTimeNotInsertionOrder(){AssetHistoryBucket b=new AssetHistoryBucket(1,0,180000);for(long at:new long[]{120000,0,60000})b.merge(sample(at,"0"));assertEquals(0L,b.highAt);assertEquals(0L,b.lowAt);assertEquals(120000L,b.closeAt);assertEquals("COMPLETE",b.quality());}
    @Test void utcBucketsHaveFixedBoundaries(){assertEquals(0,AssetHistoryBucket.floor(14399999,14400000));assertEquals(14400000,AssetHistoryBucket.floor(14400000,14400000));assertEquals(-60000,AssetHistoryBucket.floor(-1,60000));}
}
