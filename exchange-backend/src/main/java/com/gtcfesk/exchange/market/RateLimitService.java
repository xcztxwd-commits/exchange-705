package com.gtcfesk.exchange.market;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 请求限流服务
 * 规则：
 * 1. 每1秒只能1次请求
 * 2. /batch-kline接口需间隔3秒
 * 3. 所有接口相加，1分钟最大请求60次(1秒1次)
 * 4. 每天总共最大可请求86400次，超过则第二天凌晨恢复使用
 */
@Service
public class RateLimitService {
    
    private static final long MIN_INTERVAL = 1000; // 1秒
    private static final long BATCH_KLINE_INTERVAL = 3000; // 3秒
    private static final int MAX_PER_MINUTE = 60;
    private static final long MAX_PER_DAY = 86400;
    
    private long lastRequestTime = 0;
    private long lastBatchKlineTime = 0;
    private final AtomicLong requestCountPerMinute = new AtomicLong(0);
    private final AtomicLong requestCountPerDay = new AtomicLong(0);
    private long lastMinuteResetTime = System.currentTimeMillis();
    private long lastDayResetTime = getTodayStartTime();
    
    private final ReentrantLock lock = new ReentrantLock();
    
    private long getTodayStartTime() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
    
    private void resetCountersIfNeeded() {
        long now = System.currentTimeMillis();
        
        // 每分钟重置计数器
        if (now - lastMinuteResetTime >= 60000) {
            requestCountPerMinute.set(0);
            lastMinuteResetTime = now;
        }
        
        // 每天重置计数器
        long todayStart = getTodayStartTime();
        if (todayStart > lastDayResetTime) {
            requestCountPerDay.set(0);
            lastDayResetTime = todayStart;
        }
    }
    
    /**
     * 检查是否可以执行请求
     * @param isBatchKline 是否为batch-kline请求
     * @return 需要等待的毫秒数，0表示可以立即执行
     */
    public long checkAndWait(boolean isBatchKline) {
        lock.lock();
        try {
            resetCountersIfNeeded();
            
            long now = System.currentTimeMillis();
            
            // 检查每天限制
            if (requestCountPerDay.get() >= MAX_PER_DAY) {
                long tomorrowStart = getTodayStartTime() + 86400000;
                long waitTime = tomorrowStart - now;
                if (waitTime > 0) {
                    return waitTime;
                }
                resetCountersIfNeeded();
            }
            
            // 检查每分钟限制
            if (requestCountPerMinute.get() >= MAX_PER_MINUTE) {
                long waitTime = 60000 - (now - lastMinuteResetTime);
                if (waitTime > 0) {
                    return waitTime;
                }
                resetCountersIfNeeded();
            }
            
            // 检查请求间隔
            long timeSinceLastRequest = now - lastRequestTime;
            long waitTime = 0;
            
            if (isBatchKline) {
                long timeSinceLastBatchKline = now - lastBatchKlineTime;
                if (timeSinceLastBatchKline < BATCH_KLINE_INTERVAL) {
                    waitTime = BATCH_KLINE_INTERVAL - timeSinceLastBatchKline;
                }
            } else {
                if (timeSinceLastRequest < MIN_INTERVAL) {
                    waitTime = MIN_INTERVAL - timeSinceLastRequest;
                }
            }
            
            return waitTime;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 记录请求完成
     * @param isBatchKline 是否为batch-kline请求
     */
    public void recordRequest(boolean isBatchKline) {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            lastRequestTime = now;
            if (isBatchKline) {
                lastBatchKlineTime = now;
            }
            requestCountPerMinute.incrementAndGet();
            requestCountPerDay.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }
}

