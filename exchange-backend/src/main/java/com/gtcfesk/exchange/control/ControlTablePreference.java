package com.gtcfesk.exchange.control;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
/** Independent CONTROL preferences: no tenant business identity and no tokens in keys. */
@Getter @Setter @Entity @Table(name="control_table_preference",uniqueConstraints=@UniqueConstraint(columnNames={"actor_id","table_key"}))
public class ControlTablePreference {
 @Id @Column(length=160) private String id;
 @Column(name="actor_id",nullable=false) private Long actorId;
 @Column(name="table_key",length=100,nullable=false) private String tableKey;
 @Lob @Column(nullable=false) private String columnsJson;
}
