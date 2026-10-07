package com.gtcfesk.exchange.simulation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.net.*;
import java.util.*;

/** The demo process has no credentials for the real money database. */
@Component
public class SimulationGateway {
    @Value("${simulation.identity-url:http://backend:8080/api/simulation}") private String identityUrl;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.TenantRepository tenants;
    private final RestTemplate http;
    private final SimpleClientHttpRequestFactory transport;
    public SimulationGateway() {
        transport = new SimpleClientHttpRequestFactory();
        transport.setConnectTimeout(2000); transport.setReadTimeout(4000); http = new RestTemplate(transport);
    }
    public Map<String,Object> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders(); headers.set("Authorization", bearer); headers.set("X-Account-Mode", "REAL");
        Long tenant = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        com.gtcfesk.exchange.control.Tenant registration=tenants.findById(tenant).orElseThrow(IllegalArgumentException::new);
        String domain=registration.getFrontendHost();
        if (domain == null || !registration.isDomainVerified() || "DISABLED".equals(registration.getStatus())) throw new IllegalStateException("Missing verified tenant domain");
        domain = com.gtcfesk.exchange.control.TenantHostService.normalizeHost(domain);
        headers.set("X-Forwarded-Host", domain);
        URI endpoint = URI.create(identityUrl + path), target = endpoint;
        if ("http".equals(endpoint.getScheme())) {
            // HttpURLConnection ignores a manually set Host. An HTTP proxy route keeps the
            // connection on the configured identity service and sends the tenant Host on wire.
            int port = endpoint.getPort() < 0 ? 80 : endpoint.getPort();
            transport.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(endpoint.getHost(), port)));
            target = org.springframework.web.util.UriComponentsBuilder.fromUri(endpoint).host(domain).build().toUri();
        } else if (!"https".equals(endpoint.getScheme()) || !domain.equals(endpoint.getHost())) {
            throw new IllegalStateException("HTTPS identity service must use the verified tenant host");
        }
        Map<String,Object> body = http.exchange(target, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
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
