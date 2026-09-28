package com.gtcfesk.exchange.security;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.admin.AdminDataInitializer;
import com.gtcfesk.exchange.market.*;
import com.gtcfesk.exchange.service.EmailService;
import com.gtcfesk.exchange.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="security.test.redis.port", matches="[0-9]+")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:registration_captcha;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "logging.level.root=ERROR", "spring.redis.host=127.0.0.1", "spring.redis.timeout=1s"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class RealRegistrationFlowTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.port", () -> System.getProperty("security.test.redis.port"));
        registry.add("jwt.secret", () -> Base64.getEncoder().encodeToString("registration-isolated-test-secret-32bytes".getBytes()));
    }
    @MockBean ForexQuoteMarketService quotes;
    @MockBean MarketInstrumentCatalog catalog;
    @MockBean MarketOrderProcessor processor;
    @MockBean RedisMarketService marketRedis;
    @MockBean EmailService mail;
    @MockBean AdminDataInitializer initializer;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired UserAccountRepository users;
    @Autowired AssetAccountRepository assets;

    MvcResult postJson(String endpoint, Object body) throws Exception {
        return mvc.perform(post(endpoint).contentType("application/json").content(json.writeValueAsBytes(body))).andReturn();
    }
    @Test void anonymousIssueRefreshWrongAnswerSuccessReplayAndNoBypass() throws Exception {
        String session=UUID.randomUUID().toString().replace("-", "");
        Map<String,Object> issue=new HashMap<>(); issue.put("captchaSession",session);
        MvcResult generated=postJson("/api/auth/captcha",issue);
        assertEquals(200,generated.getResponse().getStatus());
        assertEquals("no-store",generated.getResponse().getHeader("Cache-Control"));
        JsonNode old=json.readTree(generated.getResponse().getContentAsString());
        assertFalse(old.has("code")); assertFalse(old.has("answer"));
        JsonNode current=json.readTree(postJson("/api/auth/captcha",issue).getResponse().getContentAsString());
        String key=RegistrationSecurity.PREFIX+"challenge:"+session;
        String answer=redis.opsForValue().get(key).substring(33);
        String email=session+"@example.invalid";
        Map<String,Object> form=new HashMap<>();
        form.put("email",email); form.put("password","testSecret123"); form.put("confirmPassword","testSecret123");
        assertEquals(400,postJson("/api/auth/register",form).getResponse().getStatus());
        assertFalse(users.existsByEmail(email));
        form.put("captchaSession",session); form.put("captchaId",old.path("captchaId").asText()); form.put("captchaCode",answer);
        assertEquals(400,postJson("/api/auth/register",form).getResponse().getStatus());
        assertNotNull(redis.opsForValue().get(key));
        form.put("captchaId",current.path("captchaId").asText()); form.put("captchaCode","XXXX");
        assertEquals(400,postJson("/api/auth/register",form).getResponse().getStatus());
        assertNull(redis.opsForValue().get(key)); assertFalse(users.existsByEmail(email));
        current=json.readTree(postJson("/api/auth/captcha",issue).getResponse().getContentAsString());
        form.put("captchaId",current.path("captchaId").asText()); form.put("captchaCode",redis.opsForValue().get(key).substring(33).toLowerCase(Locale.ROOT));
        assertEquals(200,postJson("/api/auth/register",form).getResponse().getStatus());
        assertEquals(3,assets.findByUserId(users.findByEmail(email).get().getId()).size());
        assertNull(redis.opsForValue().get(key));
        assertEquals(400,postJson("/api/auth/register",form).getResponse().getStatus());
        // Valid format + no matching session/challenge cannot create a second account.
        form.put("email","other"+email); form.put("captchaSession",UUID.randomUUID().toString().replace("-",""));
        assertEquals(400,postJson("/api/auth/register",form).getResponse().getStatus());
        assertFalse(users.existsByEmail("other"+email));
    }
}
