package com.gtcfesk.exchange.activity;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class ActivityContentPatch {
 private Long rowVersion;
 private String name;
 private String translations;
 private String defaultLocale;
 private String layoutJson;
}
