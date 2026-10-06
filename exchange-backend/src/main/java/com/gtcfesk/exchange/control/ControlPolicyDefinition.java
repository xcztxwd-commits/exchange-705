package com.gtcfesk.exchange.control;

import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Global CONTROL definitions. Defaults become scoped tenant policy rows, never credentials. */
@Getter @Setter @Entity @Table(name="control_policy_definition")
public class ControlPolicyDefinition {
 @Id @Column(name="policy_key",length=128,nullable=false) private String key;
 @Column(name="policy_name",length=128,nullable=false) private String name;
 @Column(name="options_json",columnDefinition="LONGTEXT",nullable=false) private String optionsJson;
 @Column(name="default_value",columnDefinition="TEXT",nullable=false) private String defaultValue;
 @Version @Column(nullable=false) private Long version;
}
