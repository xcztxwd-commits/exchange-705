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
    @Value("${simulation.inspection-key:}") private String inspectionKey;
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

    /** Internal remarks are fetched with the service key, never returned by the public session API. */
    public List<Map<String,Object>> profiles(List<Long> ids) {
        if (inspectionKey.length() < 32 || ids.isEmpty() || ids.size() > 100)
            throw new IllegalStateException("Simulation identity mirror is not configured");
        Long tenant = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Simulation-Inspection-Key", inspectionKey);
        headers.set("X-Simulation-Tenant-Id", tenant.toString());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(4000);
        Map<String,Object> result = new RestTemplate(factory).exchange(URI.create(identityUrl + "/user-profiles"),
                HttpMethod.POST, new HttpEntity<>(ids, headers), Map.class).getBody();
        if (result == null || !"REAL".equals(result.get("environment"))
                || !(result.get("tenantId") instanceof Number)
                || ((Number) result.get("tenantId")).longValue() != tenant || !(result.get("list") instanceof List))
            throw new IllegalStateException("Identity profile tenant mismatch");
        List<Map<String,Object>> profiles = (List<Map<String,Object>>) result.get("list");
        Set<Long> seen = new HashSet<>();
        for (Map<String,Object> profile : profiles) {
            Long id = profile.get("id") instanceof Number ? ((Number) profile.get("id")).longValue() : null;
            if (id == null || !ids.contains(id) || !seen.add(id) || !(profile.get("tenant_id") instanceof Number)
                    || ((Number) profile.get("tenant_id")).longValue() != tenant
                    || !profile.keySet().containsAll(SimulationProfileBoundary.COLUMNS)
                    || !(profile.get("email") instanceof String) || ((String) profile.get("email")).trim().isEmpty())
                throw new IllegalStateException("Invalid canonical user profile");
        }
        return profiles;
    }
}
