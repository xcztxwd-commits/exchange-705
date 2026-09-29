package com.gtcfesk.exchange.simulation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import lombok.RequiredArgsConstructor;

/** Process-level boundary. A request header can never enable the KYC exemption. */
@Component @RequiredArgsConstructor
public class SimulationEnvironment {
    private final DataSource dataSource;
    @Value("${simulation.enabled:false}") private boolean enabled;
    public boolean enabled() { return enabled; }
    @PostConstruct public void verifyIsolation() throws Exception {
        if (!enabled) return;
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            if (catalog == null || !catalog.toLowerCase(java.util.Locale.ROOT).endsWith("_demo"))
                throw new IllegalStateException("Simulation requires a dedicated database ending in _demo; refusing to start");
        }
    }
}
