package com.gtcfesk.exchange.support;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity @Getter @Setter @Table(name = "support_presence")
public class SupportPresence extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id private Long adminId;
    private boolean accepting;
    private Instant heartbeatAt;
}
