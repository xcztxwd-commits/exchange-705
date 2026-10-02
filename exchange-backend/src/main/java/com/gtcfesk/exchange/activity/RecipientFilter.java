package com.gtcfesk.exchange.activity;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;
import java.util.*;

/** Server-side recipient search; IDs mean ANY claimed campaign. */
@Getter @Setter
public class RecipientFilter {
 private String query;
 @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) private LocalDateTime createdFrom,createdTo,lastLoginFrom,lastLoginTo;
 private List<Long> claimedCampaignIds=new ArrayList<>();
 private String claimedMode="INCLUDE";
 private int page=0,size=20;
 public String stableKey(){return Arrays.asList(query==null?"":query.trim().toLowerCase(Locale.ROOT),createdFrom,createdTo,lastLoginFrom,lastLoginTo,
  claimedCampaignIds==null?Collections.emptyList():new TreeSet<>(claimedCampaignIds),claimedMode).toString();}
}
