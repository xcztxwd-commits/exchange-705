package com.gtcfesk.exchange.activity;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Entity @Table(name="activity_material")
public class ActivityMaterial {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,length=80) private String name;
 @Column(nullable=false,columnDefinition="LONGTEXT") private String nodesJson;
 private boolean deleted;
}
