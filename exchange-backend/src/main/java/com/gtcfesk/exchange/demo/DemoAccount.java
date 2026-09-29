package com.gtcfesk.exchange.demo;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "demo_account")
public class DemoAccount {
    @Id public Long userId;
    @Column(nullable = false, precision = 32, scale = 8) public BigDecimal cash;
    @Column(nullable = false) public int generation = 1;
    public Instant lastResetAt;
    public String lastResetKey;
    @Version public Long version;
}
