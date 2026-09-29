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
    private final RestTemplate http;
    public SimulationGateway() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(4000); http = new RestTemplate(factory);
    }
    public Map<String,Object> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders(); headers.set("Authorization", bearer); headers.set("X-Account-Mode", "REAL");
        Map<String,Object> body = http.exchange(identityUrl + path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
        if (body == null) throw new IllegalStateException("Identity service unavailable");
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
