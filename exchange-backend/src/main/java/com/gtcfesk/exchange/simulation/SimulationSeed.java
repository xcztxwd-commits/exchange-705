package com.gtcfesk.exchange.simulation;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Entity @Table(name="simulation_seed")
public class SimulationSeed {
    @Id public Long userId;
    @Column(nullable=false,precision=32,scale=16) public BigDecimal amountPerWallet;
    @Column(nullable=false) public LocalDateTime createdAt;
}
