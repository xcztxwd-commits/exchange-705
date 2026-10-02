package com.gtcfesk.exchange.security;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class EmailRateLimitTest {
 static class Redis extends StringRedisTemplate {List<String> keys;Object[] limits;Long result=0L;@SuppressWarnings("unchecked") @Override public <T>T execute(RedisScript<T> script,List<String> keys,Object...args){assertSame(RegistrationSecurity.LIMIT,script);this.keys=keys;this.limits=args;return (T)result;}}
 @AfterEach void clear(){TenantContext.clear();}
 @Test void rateKeysNormalizeHashAndScopeRecipients(){Redis redis=new Redis();RegistrationSecurity service=new RegistrationSecurity(redis,mock(WebsiteSecuritySettings.class));TenantContext.open(1L);service.limitEmail("Recipient@Example.com");List<String> first=new ArrayList<>(redis.keys);assertTrue(first.get(0).contains("tenant:1:mail:global"));assertFalse(first.get(1).contains("Recipient"));assertArrayEquals(new Object[]{"60","1"},redis.limits);service.limitEmail(" recipient@example.com ");assertEquals(first,redis.keys);TenantContext.clear();TenantContext.open(2L);service.limitEmail("recipient@example.com");assertNotEquals(first,redis.keys);}
 @Test void limiterFailsClosedOnLimitOrRedisFailure(){Redis redis=new Redis();RegistrationSecurity service=new RegistrationSecurity(redis,mock(WebsiteSecuritySettings.class));TenantContext.open(1L);redis.result=30L;assertEquals(429,assertThrows(SecurityFailure.class,()->service.limitEmail("r@example.com")).status);redis.result=null;assertEquals(503,assertThrows(SecurityFailure.class,()->service.limitEmail("r@example.com")).status);}
}
