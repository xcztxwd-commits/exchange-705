package com.gtcfesk.exchange.insights;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.*;
@Entity @Getter @Setter
@org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="calendar_event",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","environment","eventId"}),indexes=@Index(name="calendar_window",columnList="tenant_id,environment,releaseDate"))
public class CalendarEvent extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=4,updatable=false) private String environment;
    @Column(nullable=false,length=36,updatable=false) private String eventId;
    @Column(nullable=false,length=40) private String metric;
    private LocalDate releaseDate;
    private Instant releaseAt;
    @Column(nullable=false,length=24) private String status;
    @Column(nullable=false) private boolean published;
    @Column(nullable=false) private boolean manualLock;
    @Lob @Column(nullable=false) private String dataJson;
    /** Bounded per-channel fingerprints prevent cached sources from creating repeated skip audits. */
    @Column(length=1024) private String upstreamHash;
    @Column(nullable=false) private Instant updatedAt;
    @Version private long rowVersion;
}
