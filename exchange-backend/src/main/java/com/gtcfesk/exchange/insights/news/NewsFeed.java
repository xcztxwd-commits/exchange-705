package com.gtcfesk.exchange.insights.news;
import lombok.Getter;import lombok.Setter;import javax.persistence.*;import java.time.*;
/** Shared public metadata cache, partitioned by environment. No secrets or tenant content. */
@Entity @Getter @Setter @Table(name="news_feed")
public class NewsFeed {
    @Id @Column(length=40) private String id;
    @Column(nullable=false,length=4) private String environment;
    @Column(nullable=false,length=24) private String sourceId;
    @Column(nullable=false,length=24) private String status="NEVER_FETCHED";
    private Instant lastAttempt,lastSuccess,contentAsOf,nextAttempt,leaseUntil;
    private LocalDate budgetDate;
    private int requestsToday,failures,itemCount,skippedCount;
    private Integer httpStatus;
    @Column(length=200) private String lastError;
    @Column(length=300) private String etag,lastModified;
    @Column(length=64) private String responseHash;
    @Lob private String parsedJson;
    @Version private long rowVersion;
}
