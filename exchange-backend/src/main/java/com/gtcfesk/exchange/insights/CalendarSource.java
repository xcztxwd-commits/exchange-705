package com.gtcfesk.exchange.insights;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.*;
/** Shared official public responses only. No user or administrator content in this table. */
@Entity @Getter @Setter @Table(name="calendar_source")
public class CalendarSource {
    @Id @Column(length=48) private String id;
    @Column(nullable=false,length=4) private String environment;
    @Column(nullable=false,length=24) private String sourceId;
    @Column(nullable=false,length=24) private String status="NEVER_FETCHED";
    private Instant lastAttempt,lastSuccess,nextAttempt,leaseUntil;
    private LocalDate budgetDate;
    private int requestsToday,failures;
    private Integer httpStatus;
    @Column(length=500) private String lastError;
    @Column(length=300) private String etag,lastModified;
    @Lob private String payload;
    @Lob private String parsedJson;
    @Version private long rowVersion;
}
