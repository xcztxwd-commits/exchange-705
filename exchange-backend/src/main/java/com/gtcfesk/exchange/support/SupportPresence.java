package com.gtcfesk.exchange.support;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

@Entity @Getter @Setter @Table(name = "support_presence")
public class SupportPresence {
    @Id private Long adminId;
    private boolean accepting;
    private Instant heartbeatAt;
}
