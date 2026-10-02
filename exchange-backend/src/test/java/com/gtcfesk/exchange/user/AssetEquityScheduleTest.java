package com.gtcfesk.exchange.user;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Connection;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetEquityScheduleTest {
    @Test void boundaryAndCrossMinuteDelay() throws Exception {
        assertEquals("0 * * * * *",AssetEquityJobs.class.getMethod("minuteTick").getAnnotation(Scheduled.class).cron());
        assertEquals("20 1 * * * *",AssetEquityJobs.class.getMethod("hourTick").getAnnotation(Scheduled.class).cron());
        assertEquals("20 2 0/4 * * *",AssetEquityJobs.class.getMethod("fourHourTick").getAnnotation(Scheduled.class).cron());
        assertEquals("20 3 0 * * *",AssetEquityJobs.class.getMethod("dayTick").getAnnotation(Scheduled.class).cron());
        assertEquals("UTC",AssetEquityJobs.class.getMethod("dayTick").getAnnotation(Scheduled.class).zone());
        assertTrue(AssetEquityJobs.withinCaptureMinute(120000,120000));
        assertTrue(AssetEquityJobs.withinCaptureMinute(120000,179999));
        assertFalse(AssetEquityJobs.withinCaptureMinute(120000,180000));
        assertFalse(AssetEquityJobs.withinCaptureMinute(120000,119999));
    }
    @Test void allSchedulesSealExactlyThePreviousHalfOpenPeriod() {
        long[] delays={0,80000,140000,200000};
        for(int level=1;level<=3;level++) {
            AssetEquityStore store=mock(AssetEquityStore.class);
            AssetEquityJobs jobs=spy(new AssetEquityJobs(store,mock(EquityValuationService.class)));
            AssetEquityStore.Session session=new AssetEquityStore.Session(mock(Connection.class));
            long size=AssetEquityStore.INTERVALS[level],start=level==1?3600000:0,end=start+size;
            when(store.state(session.db,"rollup_"+level,start)).thenReturn(new long[]{start,0});
            if(level>1)when(store.state(session.db,"rollup_"+(level-1),start)).thenReturn(new long[]{end,0});
            doReturn(java.util.Collections.emptyList()).when(jobs).users(session.db,level-1,start,end,0);
            try {
                jobs.finalizeLevel(session,level,start,end+delays[level]-1);
                verify(jobs,never()).users(any(JdbcTemplate.class),anyInt(),anyLong(),anyLong(),anyLong());
                jobs.finalizeLevel(session,level,start,end+delays[level]);
                verify(jobs).users(session.db,level-1,start,end,0);
                verify(store).progress(session.db,"rollup_"+level,end,0,end+delays[level]);
            } finally {jobs.stop();}
        }
    }
    @Test void lateQueuedCaptureDoesNotReadOrBackfill(){
        AssetEquityStore store=mock(AssetEquityStore.class);
        EquityValuationService valuation=mock(EquityValuationService.class);
        AssetEquityJobs jobs=new AssetEquityJobs(store,valuation);
        doAnswer(invocation->{ ((java.util.function.Consumer<AssetEquityStore.Session>)invocation.getArgument(1)).accept(null); return null; }).when(store).locked(eq("capture"),any());
        try { jobs.captureBoundary(0); verifyNoInteractions(valuation); } finally {jobs.stop();}
    }
    @Test void parentWaitsForChildWatermarkAndRecoveryDelay() throws Exception {
        AssetEquityStore store=mock(AssetEquityStore.class);
        AssetEquityJobs jobs=spy(new AssetEquityJobs(store,mock(EquityValuationService.class)));
        AssetEquityStore.Session session=new AssetEquityStore.Session(mock(Connection.class));
        when(store.state(session.db,"rollup_1",0)).thenReturn(new long[]{0,0});
        when(store.state(session.db,"rollup_2",0)).thenReturn(new long[]{0,0});
        try {
            jobs.finalizeLevel(session,2,0,14400000+600000);
            verify(jobs,never()).users(any(JdbcTemplate.class),anyInt(),anyLong(),anyLong(),anyLong());
            jobs.finalizeLevel(session,1,0,3600000+79999);
            verify(jobs,never()).users(any(JdbcTemplate.class),anyInt(),anyLong(),anyLong(),anyLong());
            when(store.state(session.db,"rollup_2",0)).thenReturn(new long[]{86400000,0});
            when(store.state(session.db,"rollup_3",0)).thenReturn(new long[]{0,0});
            jobs.finalizeLevel(session,3,0,86400000+200000-1);
            verify(jobs,never()).users(any(JdbcTemplate.class),anyInt(),anyLong(),anyLong(),anyLong());
            doReturn(java.util.Collections.emptyList()).when(jobs).users(session.db,2,0,86400000,0);
            jobs.finalizeLevel(session,3,0,86400000+200000);
            verify(jobs).users(session.db,2,0,86400000,0);
        } finally {jobs.stop();}
    }
}
