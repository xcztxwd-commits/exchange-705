package com.gtcfesk.exchange.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RegistrationSecurity {
    private final StringRedisTemplate redis;
    private final WebsiteSecuritySettings settings;
    // Shared hash tag makes multi-key scripts work on Redis Cluster as well.
    static String prefix() { return "security:{registration}:tenant:" + com.gtcfesk.exchange.tenant.TenantContext.requireTenantId() + ":"; }
    static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>(
        "local wait=0; for i,k in ipairs(KEYS) do " +
        "local n=tonumber(redis.call('GET',k) or '0'); if n>=tonumber(ARGV[i]) then " +
        "wait=math.max(wait,redis.call('TTL',k),1); end; end; " +
        "if wait>0 then return wait; end; for i,k in ipairs(KEYS) do " +
        "if redis.call('INCR',k)==1 then redis.call('EXPIRE',k,60); end; end; return 0;", Long.class);
    static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>(
        "if redis.call('GET',KEYS[1])==ARGV[1] then " +
        "redis.call('SET',KEYS[1],ARGV[2],'EX',120); return 1; end; return 0;", Long.class);
    static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>(
        "local v=redis.call('GET',KEYS[1]); if not v then return nil; end; " +
        // An old tab/request must not consume a newer challenge.
        "if string.sub(v,1,32)~=ARGV[1] then return nil; end; " +
        "redis.call('DEL',KEYS[1]); return string.sub(v,34);", String.class);

    public void limitNetwork(String action, String address) {
        WebsiteSecuritySettings.Policy p = settings.read();
        boolean captcha = "captcha".equals(action);
        limit(Arrays.asList(prefix()+"rate:"+action+":global", prefix()+"rate:"+action+":ip:"+address),
            captcha ? p.getCaptchaGlobalPerMinute() : p.getRegisterGlobalPerMinute(),
            captcha ? p.getCaptchaIpPerMinute() : p.getRegisterIpPerMinute());
    }
    public void limitEmail(String recipient) {
        try {
            String digest=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(recipient.trim().toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            limit(Arrays.asList(prefix()+"mail:global",prefix()+"mail:recipient:"+digest),60,1);
        }catch(SecurityFailure e){throw e;}catch(Exception e){throw SecurityFailure.unavailable();}
    }
    /** Separate fixed-purpose SMS contract, not wired into registration or login. */
    public void limitSms(String recipientDigest){
        if(recipientDigest==null||!recipientDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("短信收件人摘要无效");
        limit(Arrays.asList(prefix()+"sms:issue:global",prefix()+"sms:issue:recipient:"+recipientDigest),30,1);
    }
    public void limitSmsVerify(String requestId){
        validateId(requestId);limit(Collections.singletonList(prefix()+"sms:consume:"+requestId),5);
    }
    private void limitSession(String action, String session) {
        WebsiteSecuritySettings.Policy p = settings.read();
        limit(Collections.singletonList(prefix()+"rate:"+action+":session:"+session),
            "captcha".equals(action) ? p.getCaptchaSessionPerMinute() : p.getRegisterSessionPerMinute());
    }
    private void limit(List<String> keys, Integer... limits) {
        try {
            Object[] args = Arrays.stream(limits).map(String::valueOf).toArray();
            Long wait = redis.execute(LIMIT, keys, args);
            if (wait == null) throw SecurityFailure.unavailable();
            if (wait > 0) throw new SecurityFailure(429, "SECURITY_RATE_LIMITED", wait);
        } catch (SecurityFailure e) { throw e; }
        catch (Exception e) { throw SecurityFailure.unavailable(); }
    }
    static void validateId(String id) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw SecurityFailure.invalid();
    }
    public Map<String, Object> create(String session) {
        validateId(session);
        limitSession("captcha", session);
        String id = UUID.randomUUID().toString().replace("-", "");
        String key = prefix()+"challenge:"+session, pending = id+"|pending";
        try {
            // Invalidate the previous answer BEFORE image generation, even if generation fails.
            redis.opsForValue().set(key, pending, Duration.ofSeconds(120));
            CombinedCaptcha captcha = new CombinedCaptcha();
            captcha.createCode();
            String image = captcha.getImageBase64Data();
            Long published = redis.execute(PUBLISH, Collections.singletonList(key), pending, id+"|"+captcha.getCode());
            if (published == null) throw SecurityFailure.unavailable();
            if (!Long.valueOf(1).equals(published)) throw new SecurityFailure(409, "CAPTCHA_REPLACED", 0);
            Map<String,Object> result = new HashMap<>();
            result.put("captchaId", id); result.put("image", image); result.put("expiresIn", 120);
            return result;
        } catch (SecurityFailure e) { throw e; }
        catch (Exception e) { throw SecurityFailure.unavailable(); }
    }
    public void verifyAndConsume(String session, String id, String input) {
        validateId(session); validateId(id);
        limitSession("register", session);
        try {
            String answer = redis.execute(CONSUME, Collections.singletonList(prefix()+"challenge:"+session), id);
            String actual = input == null ? "" : input.trim().toUpperCase(Locale.ROOT);
            if (answer == null || !actual.matches("[A-Z0-9]{4}") || !answer.equals(actual)) throw SecurityFailure.invalid();
        } catch (SecurityFailure e) { throw e; }
        catch (Exception e) { throw SecurityFailure.unavailable(); }
    }
}
