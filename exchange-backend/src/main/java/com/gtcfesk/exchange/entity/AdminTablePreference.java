package com.gtcfesk.exchange.entity;

import lombok.Data;
import javax.persistence.*;

@Data
@Entity
@Table(name = "admin_table_preference")
public class AdminTablePreference {
    @Id
    @Column(length = 200)
    private String id;
    @Lob
    @Column(nullable = false)
    private String columnsJson;
}
