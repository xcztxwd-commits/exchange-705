package com.gtcfesk.exchange.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

/** Bounded transport; never expose provider URLs, credentials or response bodies in errors. */
@Component
public class MarketHttp {
    @Value("${market.quote.connect-timeout-ms:2000}") int connectTimeout = 2000;
    @Value("${market.quote.read-timeout-ms:3000}") int readTimeout = 3000;
    @Value("${market.quote.batch-budget-ms:5000}") int budget = 5000;
    private final ThreadLocal<Long> deadline = new ThreadLocal<>();
    private final Semaphore yahooRequests = new Semaphore(2);
    private final ConcurrentMap<String, Long> hostRetryAt = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "market-http-deadline"); thread.setDaemon(true); return thread;
    });
    public MarketHttp() { timer.setRemoveOnCancelPolicy(true); }
    public void begin() { deadline.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, budget))); }
    public void end() { deadline.remove(); }
    private int remaining() {
        Long until = deadline.get();
        return until == null ? Math.max(1, budget) : (int) Math.max(0, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()));
    }
    public static class Failure extends RuntimeException {
        public final long retryAfterMs;
        Failure(String reason, long retryAfterMs) { super(reason); this.retryAfterMs = retryAfterMs; }
    }
    static long retryAfter(String value) {
        if (value == null) return 0;
        try { return Math.max(0, Math.multiplyExact(Long.parseLong(value.trim()), 1000)); }
        catch (Exception ignored) {
            try { return Math.max(0, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()); }
            catch (Exception invalid) { return 0; }
        }
    }
    public ResponseEntity<String> get(URI uri) {
        return get(uri, 4 * 1024 * 1024);
    }
    public ResponseEntity<String> get(URI uri, int maxBytes) {
        HttpURLConnection connection = null;
        ScheduledFuture<?> timeout = null;
        boolean yahooPermit = false;
        String rateKey = uri.getHost() + (uri.getPath().startsWith("/fapi/") ? ":futures" : uri.getPath().startsWith("/api/v3/") ? ":spot" : "");
        try {
            long hostDelay = hostRetryAt.getOrDefault(rateKey, 0L) - System.currentTimeMillis();
            if (hostDelay > 0) throw new Failure("rate_limited", hostDelay);
            if (uri.getHost() != null && uri.getHost().endsWith(".finance.yahoo.com")) {
                yahooPermit = yahooRequests.tryAcquire(remaining(), TimeUnit.MILLISECONDS);
                if (!yahooPermit) throw new Failure("budget_exhausted", 0);
            }
            int left = remaining();
            if (left <= 0) throw new Failure("budget_exhausted", 0);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(Math.min(Math.max(1, connectTimeout), left));
            connection.setReadTimeout(Math.min(Math.max(1, readTimeout), left));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setRequestProperty("Accept", "application/json");
            final HttpURLConnection active = connection;
            timeout = timer.schedule(active::disconnect, left, TimeUnit.MILLISECONDS);
            int status = connection.getResponseCode();
            if (status == 429 || status == 418) {
                long delay = Math.max(status == 418 ? 120000 : 2000, retryAfter(connection.getHeaderField("Retry-After")));
                hostRetryAt.merge(rateKey, System.currentTimeMillis() + delay, Math::max);
                throw new Failure("http_" + status, delay);
            }
            if (status < 200 || status >= 300)
                throw new Failure("http_" + status, status == 429 ? retryAfter(connection.getHeaderField("Retry-After")) : 0);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (remaining() <= 0) throw new Failure("budget_exhausted", 0);
                    if (output.size() + count > maxBytes) throw new Failure("response_too_large", 0);
                    output.write(buffer, 0, count);
                }
                return ResponseEntity.ok(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Failure failure) { throw failure; }
        catch (Exception failure) { throw new Failure(failure instanceof SocketTimeoutException ? "timeout" : "connection_failure", 0); }
        finally {
            if (yahooPermit) yahooRequests.release();
            if (timeout != null) timeout.cancel(false);
            if (connection != null) connection.disconnect();
        }
    }
    @PreDestroy public void stop() { timer.shutdownNow(); }
}
