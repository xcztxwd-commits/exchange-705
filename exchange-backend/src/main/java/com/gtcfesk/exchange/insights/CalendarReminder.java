package com.gtcfesk.exchange.insights;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;
@Entity @Getter @Setter
@org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="calendar_reminder",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","environment","userId","eventId","leadMinutes"}),indexes=@Index(name="calendar_reminder_pending",columnList="tenant_id,environment,enabled,eventId"))
public class CalendarReminder extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=4,updatable=false) private String environment;
    @Column(nullable=false,updatable=false) private Long userId;
    @Column(nullable=false,length=36,updatable=false) private String eventId;
    @Column(nullable=false,updatable=false) private int leadMinutes;
    @Column(nullable=false,length=64) private String timezone;
    @Column(nullable=false) private boolean enabled;
    private Instant deliveredAt;
    private Instant deliveredReleaseAt;
    private Long letterId;
    @Column(nullable=false) private Instant updatedAt;
    @Version private long rowVersion;
}
