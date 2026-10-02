package com.gtcfesk.exchange.simulation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.util.*;

/** The demo process has no credentials for the real money database. */
@Component
public class SimulationGateway {
    @Value("${simulation.identity-url:http://backend:8080/api/simulation}") private String identityUrl;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantRepository tenants;
    private final RestTemplate http;
    public SimulationGateway() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(4000); http = new RestTemplate(factory);
    }
    public Map<String,Object> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders(); headers.set("Authorization", bearer); headers.set("X-Account-Mode", "REAL");
        Long tenant = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        com.gtcfesk.exchange.control.Tenant registration=tenants.findById(tenant).orElseThrow(IllegalArgumentException::new);
        String domain=registration.getFrontendHost();
        if (domain == null || !registration.isDomainVerified() || "DISABLED".equals(registration.getStatus())) throw new IllegalStateException("Missing verified tenant domain");
        headers.set("X-Forwarded-Host", domain);
        Map<String,Object> body = http.exchange(identityUrl + path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
        if (body == null || !(body.get("tenantId") instanceof Number) || ((Number)body.get("tenantId")).longValue() != tenant) throw new IllegalStateException("Identity tenant mismatch");
        return body;
    }
    public Long authenticate(String bearer) {
        Map<String,Object> session = get("/session", bearer);
        if (!"REAL".equals(session.get("environment"))) throw new IllegalStateException("Identity service must be the real environment");
        Long id = Long.valueOf(session.get("userId").toString());
        if (id <= 0) throw new IllegalArgumentException("Invalid user identity");
        return id;
    }
}
