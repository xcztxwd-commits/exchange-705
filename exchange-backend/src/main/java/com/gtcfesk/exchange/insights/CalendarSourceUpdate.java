package com.gtcfesk.exchange.insights;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;
@Entity @Getter @Setter @Table(name="calendar_source_update",indexes=@Index(name="calendar_fetch_history",columnList="environment,sourceId,id"))
public class CalendarSourceUpdate {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=4) private String environment;
    @Column(nullable=false,length=24) private String sourceId;
    @Column(nullable=false,length=24) private String status;
    private Integer httpStatus;
    @Column(length=64) private String responseHash;
    @Column(length=500) private String error;
    @Column(nullable=false) private Instant capturedAt;
}
