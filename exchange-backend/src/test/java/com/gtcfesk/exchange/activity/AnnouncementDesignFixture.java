package com.gtcfesk.exchange.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class AnnouncementDesignFixture {
 private AnnouncementDesignFixture(){}
 static ObjectNode design(ObjectMapper mapper){
  ObjectNode root=mapper.createObjectNode();root.put("version",1).put("presentation","announcement");
  root.putObject("dialog").put("width",760).put("radius",22).put("blur",5).put("backdrop","#17221973");
  ObjectNode locales=root.putObject("locales");
  for(String locale:new String[]{"zh-CN","zh-TW","ja"}){
   ObjectNode language=locales.putObject(locale),page=language.putArray("pages").addObject();
   page.put("id","gift").put("name","公告").putArray("nodes");
   ObjectNode announcement=language.putObject("announcement");
   for(String field:new String[]{"label","milestone","milestoneLabel","title","subtitle","period","greeting","intro","depositHeading","rewardHeading","currency","currencyNote","rulesHeading","tierRule","withdrawalHeading","withdrawalRule","withdrawalRights","tradingRule","durationRule","supportRule","signoff","footerNote","supportLabel","acknowledgeLabel"})announcement.put(field,field+" "+locale);
   announcement.put("title",locale.equals("ja")?"100万人達成記念":"百万用户庆典");
   announcement.put("period","2026年10月21日 — 2026年11月21日");
   ArrayNode tiers=announcement.putArray("tiers");
   for(String[] tier:new String[][]{{"30,000","18,888"},{"50,000","38,888"},{"80,000","58,888"},{"100,000","88,888"}})tiers.addObject().put("deposit",tier[0]).put("reward",tier[1]);
  }
  return root;
 }
 static ObjectNode content(ObjectNode design){return (ObjectNode)design.path("locales").path("zh-CN").path("announcement");}
}
