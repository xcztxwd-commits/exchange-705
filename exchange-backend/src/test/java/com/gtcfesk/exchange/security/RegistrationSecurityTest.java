package com.gtcfesk.exchange.security;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.auth.dto.RegisterRequest;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class RegistrationSecurityTest {
    @Test void combinedImageAndGeneratorBounds() {
        Set<Integer> lines = new HashSet<>(), circles = new HashSet<>(), warps = new HashSet<>();
        for (int i=0; i<120; i++) {
            CombinedCaptcha c = new CombinedCaptcha(); c.createCode();
            assertTrue(c.getCode().matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}"));
            assertTrue(c.getCode().matches(".*[A-Z].*") && c.getCode().matches(".*[0-9].*"));
            assertTrue(c.lines>=3 && c.lines<=9); assertTrue(c.circles>=1 && c.circles<=5); assertTrue(c.warps>=0 && c.warps<=1);
            assertEquals(240, c.getImage().getWidth()); assertEquals(104, c.getImage().getHeight());
            assertTrue(c.verify(c.getCode().toLowerCase(Locale.ROOT))); assertFalse(c.verify("WRONG"));
            lines.add(c.lines); circles.add(c.circles); warps.add(c.warps);
        }
        assertTrue(lines.size()>1 && circles.size()>1 && warps.size()>1);
    }
    @Test void settingsFailClosedAndValidateBoundaries() {
        SystemConfigService configs = mock(SystemConfigService.class);
        WebsiteSecuritySettings settings = new WebsiteSecuritySettings(configs);
        assertEquals(30, settings.read().getCaptchaIpPerMinute());
        for (String json : Arrays.asList("null", "{}x", "{\"captchaIpPerMinute\":0}", "{\"registerGlobalPerMinute\":3001}", "{\"registerIpPerMinute\":null}", "{\"registerIpPerMinute\":1.5}", "{\"typo\":1}"))
            assertThrows(IllegalArgumentException.class, () -> WebsiteSecuritySettings.parse(json));
        when(configs.getConfigValue(anyString())).thenReturn("broken");
        assertEquals(503, assertThrows(SecurityFailure.class, settings::read).status);
        when(configs.getConfigValue(anyString())).thenThrow(new RuntimeException("db down"));
        assertEquals(503, assertThrows(SecurityFailure.class, settings::read).status);
    }
    @Test void redisFailureBlocksIssueAndConsumption() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        WebsiteSecuritySettings settings = mock(WebsiteSecuritySettings.class);
        when(settings.read()).thenReturn(new WebsiteSecuritySettings.Policy());
        RegistrationSecurity service = new RegistrationSecurity(redis, settings);
        assertEquals(503, assertThrows(SecurityFailure.class, () -> service.create(id())).status);
        assertEquals(503, assertThrows(SecurityFailure.class, () -> service.verifyAndConsume(id(), id(), "A2B3")).status);
    }
    @Test void networkLimitsApplyBeforeMalformedBodyAndIgnoreUntrustedHeaders() throws Exception {
        RegistrationSecurity service = mock(RegistrationSecurity.class);
        RegistrationSecurityFilter filter = new RegistrationSecurityFilter(service, "127.0.0.1/32");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/register");
        req.setRemoteAddr("198.51.100.20"); req.addHeader("X-Real-IP", "1.2.3.4");
        assertEquals("198.51.100.20", filter.clientAddress(req));
        req.setRemoteAddr("127.0.0.1"); assertEquals("1.2.3.4", filter.clientAddress(req));
        doThrow(new SecurityFailure(429,"SECURITY_RATE_LIMITED",17)).when(service).limitNetwork(anyString(),anyString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(); filter.doFilter(req,res,chain);
        assertEquals(429,res.getStatus()); assertEquals("17",res.getHeader("Retry-After")); assertNull(chain.getRequest());
        assertTrue(res.getContentAsString().contains("SECURITY_RATE_LIMITED"));
        req.setRequestURI("/api/auth/register/");
        MockHttpServletResponse trailing = new MockHttpServletResponse();
        filter.doFilter(req, trailing, new MockFilterChain());
        assertEquals(429, trailing.getStatus());
    }
    @Test void dtoRequiresCaptchaAndAllowsEmptyInvitation() {
        RegisterRequest req = new RegisterRequest(); req.setEmail("a@example.com"); req.setPassword("secret123"); req.setConfirmPassword("secret123");
        try (javax.validation.ValidatorFactory f=javax.validation.Validation.buildDefaultValidatorFactory()) {
            assertEquals(3,f.getValidator().validate(req).size());
            req.setCaptchaSession(id()); req.setCaptchaId(id()); req.setCaptchaCode("a2B3");
            assertTrue(f.getValidator().validate(req).isEmpty());
        }
    }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }

    @Test void realRedisRefreshReplayConcurrencyExpiryLimitsAndConfigChanges() throws Exception {
        // Explicit opt-in dedicated Redis only. Never point this test at a production database.
        String port = System.getProperty("security.test.redis.port");
        Assumptions.assumeTrue(port != null, "Run with -Dsecurity.test.redis.port=<isolated redis port>");
        org.springframework.data.redis.connection.RedisStandaloneConfiguration isolated = new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(port));
        String password=System.getenv("MT705_TEST_REDIS_PASSWORD");
        if(password!=null && !password.isBlank())isolated.setPassword(password);
        LettuceConnectionFactory connection = new LettuceConnectionFactory(isolated);
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        WebsiteSecuritySettings settings = mock(WebsiteSecuritySettings.class);
        WebsiteSecuritySettings.Policy policy = new WebsiteSecuritySettings.Policy();
        when(settings.read()).thenReturn(policy);
        RegistrationSecurity service = new RegistrationSecurity(redis, settings);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            String session=id(), key=RegistrationSecurity.prefix()+"challenge:"+session;
            Map<String,Object> first=service.create(session), second=service.create(session); final String initialSession=session;
            assertFalse(second.containsKey("code")); assertTrue(second.get("image").toString().startsWith("data:image/png;base64,"));
            assertTrue(redis.getExpire(key)>0 && redis.getExpire(key)<=120);
            assertEquals(400,assertThrows(SecurityFailure.class,()->service.verifyAndConsume(initialSession,(String)first.get("captchaId"),"A2B3")).status);
            String answer=redis.opsForValue().get(key).substring(33);
            service.verifyAndConsume(session,(String)second.get("captchaId"),answer.toLowerCase(Locale.ROOT));
            assertThrows(SecurityFailure.class,()->service.verifyAndConsume(initialSession,(String)second.get("captchaId"),answer));

            session=id(); key=RegistrationSecurity.prefix()+"challenge:"+session;
            Map<String,Object> challenge=service.create(session);
            final String wrongSession=session, wrongId=(String)challenge.get("captchaId");
            assertThrows(SecurityFailure.class,()->service.verifyAndConsume(wrongSession,wrongId,"XXXX"));
            assertNull(redis.opsForValue().get(key));

            final String concurrentSession=id(), concurrentKey=RegistrationSecurity.prefix()+"challenge:"+concurrentSession;
            Map<String,Object> concurrent=service.create(concurrentSession);
            String concurrentAnswer=redis.opsForValue().get(concurrentKey).substring(33);
            CountDownLatch start=new CountDownLatch(1);
            List<Future<Boolean>> outcomes=new ArrayList<>();
            for(int i=0;i<12;i++) outcomes.add(pool.submit(()->{try(com.gtcfesk.exchange.tenant.TenantContext.Scope scope=com.gtcfesk.exchange.tenant.TenantContext.open(1L)){start.await();try{service.verifyAndConsume(concurrentSession,(String)concurrent.get("captchaId"),concurrentAnswer);return true;}catch(SecurityFailure e){return false;}}}));
            start.countDown(); int success=0; for(Future<Boolean> result:outcomes) if(result.get())success++;
            assertEquals(1,success);

            final String expiredSession=id(), expiredKey=RegistrationSecurity.prefix()+"challenge:"+expiredSession;
            Map<String,Object> expired=service.create(expiredSession);
            redis.expire(expiredKey,Duration.ofMillis(5)); Thread.sleep(30);
            assertThrows(SecurityFailure.class,()->service.verifyAndConsume(expiredSession,(String)expired.get("captchaId"),"A2B3"));

            String ip="test-"+id(); policy.setRegisterIpPerMinute(2);
            service.limitNetwork("register",ip); service.limitNetwork("register",ip);
            SecurityFailure blocked=assertThrows(SecurityFailure.class,()->service.limitNetwork("register",ip));
            assertEquals(429,blocked.status); assertTrue(blocked.retryAfter>0 && blocked.retryAfter<=60);
            policy.setRegisterIpPerMinute(3); service.limitNetwork("register",ip);
            assertThrows(SecurityFailure.class,()->service.limitNetwork("register",ip));

            // Older image generation cannot publish over a newer refresh marker.
            String raceKey=RegistrationSecurity.prefix()+"challenge:"+id(); redis.opsForValue().set(raceKey,"new|pending");
            assertEquals(0L,redis.execute(RegistrationSecurity.PUBLISH,Collections.singletonList(raceKey),"old|pending","old|A2B3"));
            assertEquals("new|pending",redis.opsForValue().get(raceKey)); redis.delete(raceKey);
        } finally { pool.shutdownNow(); connection.destroy(); }
    }
}
