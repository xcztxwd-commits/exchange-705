package com.gtcfesk.exchange.insights;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.math.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.springframework.web.util.HtmlUtils;

/** Bounded, agency-specific parsers. An unavailable field is never inferred from an unrelated number. */
public final class CalendarParser {
    private CalendarParser() { }
    public static class Batch { public List<CalendarData> events=new ArrayList<>(),observations=new ArrayList<>(); }
    public static final String BLS_ICS="https://www.bls.gov/schedule/news_release/bls.ics",BEA_ICS="https://www.bea.gov/news/schedule/ics/online-calendar-subscription.ics",FED_CAL="https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm";
    public static final String BEA_RSS="https://apps.bea.gov/rss/rss.xml",FED_RSS="https://www.federalreserve.gov/feeds/press_monetary.xml",BLS_API="https://api.bls.gov/publicAPI/v1/timeseries/data/";
    private static final ZoneId EASTERN=ZoneId.of("America/New_York");
    private static String plain(String value){return HtmlUtils.htmlUnescape(value.replaceAll("(?s)<[^>]*>"," ")).replaceAll("\\s+"," ").trim();}
    private static String match(String regex,String text,int group){Matcher m=Pattern.compile(regex,Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(text);return m.find()?m.group(group):null;}
    private static Month month(String value){String first=value.trim().split("/")[0].trim();for(Month m:Month.values())if(m.name().toLowerCase(Locale.ROOT).startsWith(first.toLowerCase(Locale.ROOT)))return m;throw CalendarData.bad("无法解析官方月份");}
    static String monthlyPeriod(String text){String value=match("(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(20[0-9]{2})",text,0);if(value==null)return null;String[] p=value.split("\\s+");return YearMonth.of(Integer.parseInt(p[1]),month(p[0])).toString();}
    private static String quarter(String text){String q=match("([1-4])(?:st|nd|rd|th)?\\s+Quarter\\s+(?:and Year\\s+)?(20[0-9]{2})",text,0);if(q==null)q=match("Q[1-4]\\s+20[0-9]{2}",text,0);if(q==null)return null;String year=match("20[0-9]{2}",q,0);return year+"-Q"+match("[1-4]",q,0);}
    private static String stage(String text){String s=text.toLowerCase(Locale.ROOT);if(s.contains("advance"))return "ADVANCE";if(s.contains("second")||s.equals("2nd"))return "SECOND";if(s.contains("third")||s.equals("3rd"))return "THIRD";if(s.contains("updated"))return "UPDATED";if(s.contains("initial estimate"))return "INITIAL_ESTIMATE";throw CalendarData.bad("GDP 发布阶段未提供");}
    static Instant localMinute(LocalDateTime time,ZoneId zone){List<ZoneOffset> offsets=zone.getRules().getValidOffsets(time);if(offsets.size()!=1)throw CalendarData.bad("夏令时缺失或歧义时刻需明确 UTC");return time.toInstant(offsets.get(0));}
    public static Batch ics(String agency,String raw,Instant received){
        if(!raw.startsWith("BEGIN:VCALENDAR")||raw.length()>2000000||raw.contains("RRULE:")||raw.contains("RDATE:"))throw CalendarData.bad("只支持有限官方 VEVENT；不展开未知重复规则");
        String unfolded=raw.replaceAll("\\r?\\n[ \\t]","");String calendarZone=match("(?m)^X-WR-TIMEZONE:([^\\r\\n]+)",unfolded,1);if(calendarZone==null)calendarZone="America/New_York";
        Batch batch=new Batch();int count=0;
        for(String block:unfolded.split("BEGIN:VEVENT")){
            if(!block.contains("END:VEVENT"))continue;if(++count>2000)throw CalendarData.bad("日程事件过多");
            String title=match("(?m)^SUMMARY:([^\\r\\n]+)",block,1);if(title==null)throw CalendarData.bad("日程缺少标题");title=title.replace("\\,",",").replace("\\;",";").replace("\\n"," ").trim();
            List<String> metrics=new ArrayList<>();if("BLS".equals(agency)){if(title.startsWith("Employment Situation"))metrics=Arrays.asList("NFP_CHANGE","UNEMPLOYMENT_RATE");else if(title.startsWith("Consumer Price Index"))metrics=Collections.singletonList("CPI_YOY");}
            else if("BEA".equals(agency)){if(title.startsWith("Personal Income and Outlays"))metrics=Collections.singletonList("CORE_PCE_MOM");else if(title.startsWith("Gross Domestic Product,")||title.startsWith("GDP ("))metrics=Collections.singletonList("GDP_QOQ_ANNUALIZED");}
            else throw CalendarData.bad("ICS 机构无效");
            if(metrics.isEmpty())continue;
            String uid=match("(?m)^UID:([^\\r\\n]+)",block,1),date=match("(?m)^DTSTART([^:\\r\\n]*):([^\\r\\n]+)",block,2),params=match("(?m)^DTSTART([^:\\r\\n]*):",block,1);
            if(uid==null||date==null)throw CalendarData.bad("官方日程必须带 UID 和 DTSTART");
            for(String metric:metrics){CalendarData d=CalendarData.metric(metric);d.sourceUid=uid;d.statisticalPeriod=metric.equals("GDP_QOQ_ANNUALIZED")?quarter(title):monthlyPeriod(title);if(d.statisticalPeriod==null)d.statisticalPeriod="UID:"+uid;d.releaseStage=metric.equals("GDP_QOQ_ANNUALIZED")?stage(title):"INITIAL";
                d.sourceChannel=agency+"_CALENDAR";d.sourceUrl=agency.equals("BLS")?BLS_ICS:BEA_ICS;d.sourceAsOf=received;
                String tz=match("TZID=([^;]+)",params,1);d.sourceTimezone=tz==null?calendarZone:tz;if(d.sourceTimezone.equals("US/Eastern"))d.sourceTimezone="America/New_York";
                try{if(date.matches("[0-9]{8}")){d.timePrecision="DATE";d.releaseDate=LocalDate.parse(date,DateTimeFormatter.BASIC_ISO_DATE);}else{
                    d.timePrecision="MINUTE";LocalDateTime t=LocalDateTime.parse(date.replace("Z",""),DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));d.releaseAt=date.endsWith("Z")?t.toInstant(ZoneOffset.UTC):localMinute(t,ZoneId.of(d.sourceTimezone));
                }}catch(DateTimeException e){throw CalendarData.bad("官方 DTSTART 无效");}
                String seq=match("(?m)^SEQUENCE:([0-9]+)",block,1);d.sourceRevision=seq==null?0:Long.parseLong(seq);if(block.contains("STATUS:CANCELLED"))d.status="CANCELLED";d.validate();batch.events.add(d);
            }
        }
        if(batch.events.isEmpty())throw CalendarData.bad("官方日程中没有受支持事件");return batch;
    }
    private static Document xml(String raw)throws Exception{
        if(raw.length()>2000000)throw CalendarData.bad("RSS 过大");DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");return f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(raw.replaceFirst("^\\uFEFF",""))));
    }
    private static Element child(Element e,String name){for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&n.getNodeName().equals(name))return (Element)n;return null;}
    private static Element path(Element e,String...names){for(String n:names){if(e==null)return null;e=child(e,n);}return e;}
    private static String text(Element e,String...names){Element n=path(e,names);return n==null?null:n.getTextContent().trim();}
    private static Instant rssDate(String value){try{return ZonedDateTime.parse(value.replace(" EDT"," -0400").replace(" EST"," -0500"),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(Exception e){throw CalendarData.bad("RSS 时间无法核验");}}
    public static Batch beaRss(String raw,Instant received)throws Exception{
        Batch b=new Batch();NodeList items=xml(raw).getElementsByTagName("item");if(items.getLength()>1000)throw CalendarData.bad("RSS 条目过多");
        for(int i=0;i<items.getLength();i++){Element item=(Element)items.item(i);if(!"GDP".equals(item.getAttribute("name")))continue;
            if(!"PCT".equals(text(item,"changeUnit")))throw CalendarData.bad("GDP RSS 单位不匹配");
            CalendarData d=CalendarData.metric("GDP_QOQ_ANNUALIZED");d.statisticalPeriod=quarter(text(item,"data","main","current","infoDate"));d.releaseStage=stage(Optional.ofNullable(text(item,"vintage")).orElse(text(item,"title")));d.actual=signedNumber(text(item,"data","main","current","percentChange"));d.previous=signedNumber(text(item,"data","main","previous","percentChange"));d.actualBasis="OFFICIAL_RELEASE";d.sourceUrl=text(item,"link");d.sourceChannel="BEA_DATA";d.sourceAsOf=received;d.sourceUid=text(item,"guid");d.releaseAt=rssDate(text(item,"pubDate"));d.timePrecision="MINUTE";d.status="RELEASED";d.isRevised=!"ADVANCE".equals(d.releaseStage);d.validate();b.events.add(d);
        }
        if(b.events.isEmpty())throw CalendarData.bad("GDP RSS 未提供已核验数值");return b;
    }
    private static String signedNumber(String value){if(value==null||value.isEmpty())return null;String n=value.startsWith("+")?value.substring(1):value;CalendarData.number(n);return n;}
    public static Batch bls(String raw,Instant received,ObjectMapper mapper)throws Exception{
        JsonNode root=mapper.readTree(raw);if(!"REQUEST_SUCCEEDED".equals(root.path("status").asText())||root.path("message").size()!=0)throw CalendarData.bad("BLS 业务失败或带错误消息");Batch b=new Batch();
        Map<String,String> series=new LinkedHashMap<>();series.put("CES0000000001","NFP_CHANGE");series.put("LNS14000000","UNEMPLOYMENT_RATE");series.put("CUUR0000SA0","CPI_YOY");
        Set<String> found=new HashSet<>();for(JsonNode s:root.path("Results").path("series")){String id=s.path("seriesID").asText();String metric=series.get(id);if(metric==null)continue;found.add(id);Map<YearMonth,BigDecimal> values=new TreeMap<>();Map<YearMonth,String> notes=new HashMap<>();
            for(JsonNode row:s.path("data")){String period=row.path("period").asText();if(!period.matches("M(0[1-9]|1[0-2])"))continue;YearMonth p=YearMonth.of(Integer.parseInt(row.path("year").asText()),Integer.parseInt(period.substring(1)));String v=row.path("value").asText();if("-".equals(v))continue;CalendarData.number(v);values.put(p,new BigDecimal(v));notes.put(p,row.path("footnotes").toString());}
            for(YearMonth p:values.keySet()){CalendarData d=CalendarData.metric(metric);d.statisticalPeriod=p.toString();d.actual=value(metric,p,values);d.previous=value(metric,p.minusMonths(1),values);if(d.actual==null)continue;d.actualBasis="LATEST_TIME_SERIES";d.sourceUrl=BLS_API;d.sourceChannel="BLS_DATA";d.sourceAsOf=received;d.footnotes=id+" "+notes.get(p);d.status="RELEASED";d.validate();b.observations.add(d);}
        }
        if(!found.containsAll(series.keySet())||b.observations.isEmpty())throw CalendarData.bad("BLS 必需系列缺失");return b;
    }
    private static String value(String metric,YearMonth p,Map<YearMonth,BigDecimal> values){BigDecimal current=values.get(p);if(current==null)return null;if(metric.equals("UNEMPLOYMENT_RATE"))return current.toPlainString();BigDecimal before=values.get(p.minusMonths(metric.equals("CPI_YOY")?12:1));if(before==null)return null;
        if(metric.equals("NFP_CHANGE"))return current.subtract(before).toPlainString();if(before.signum()<=0)throw CalendarData.bad("CPI 指数基数无效");return current.subtract(before).multiply(new BigDecimal("100")).divide(before,1,RoundingMode.HALF_UP).toPlainString();}
    public static Batch fedCalendar(String html,Instant received){
        Batch b=new Batch();Matcher years=Pattern.compile("(20[0-9]{2}) FOMC Meetings(.*?)(?=20[0-9]{2} FOMC Meetings|$)",Pattern.DOTALL).matcher(html);int current=received.atZone(ZoneOffset.UTC).getYear();
        while(years.find()){int year=Integer.parseInt(years.group(1));if(year<current-1||year>current+1)continue;
            Matcher rows=Pattern.compile("fomc-meeting__month[^>]*><strong>(.*?)</strong>.*?fomc-meeting__date[^>]*>(.*?)</div>(.*?)(?=fomc-meeting__month|$)",Pattern.DOTALL).matcher(years.group(2));
            while(rows.find()){String months=plain(rows.group(1)),days=plain(rows.group(2)).replace("*","");if(!days.matches("[0-9]{1,2}(-[0-9]{1,2})?"))continue;Month start=month(months);String[] ds=days.split("-");int end=Integer.parseInt(ds[ds.length-1]);Month endMonth=months.contains("/")?month(months.split("/")[1]):start;LocalDate date=LocalDate.of(year,endMonth,end);
                CalendarData d=CalendarData.metric("FOMC_DECISION");d.statisticalPeriod=YearMonth.of(year,start).toString();d.releaseDate=date;d.timePrecision="DATE";d.sourceUrl=FED_CAL;d.sourceChannel="FED_CALENDAR";d.sourceAsOf=received;d.sourceUid="regular-meeting:"+d.statisticalPeriod;d.footnotes="Official regular meeting date; publication time not provided. Dates may be tentative.";String statement=match("href=\"(/newsevents/pressreleases/monetary[0-9]+a\\.htm)\"",rows.group(3),1);if(statement!=null)d.sourceUrl="https://www.federalreserve.gov"+statement;d.validate();b.events.add(d);
                String minutesDate=match("\\(Released ([A-Za-z]+ [0-9]{1,2}, 20[0-9]{2})\\)",rows.group(3),1),minutesUrl=match("href=\"(/monetarypolicy/fomcminutes[0-9]+\\.htm)\"",rows.group(3),1);
                if(minutesDate!=null&&minutesUrl!=null){CalendarData m=CalendarData.metric("FOMC_MINUTES");m.statisticalPeriod=d.statisticalPeriod;m.releaseDate=LocalDate.parse(minutesDate,DateTimeFormatter.ofPattern("MMMM d, yyyy",Locale.US));m.timePrecision="DATE";m.sourceUrl="https://www.federalreserve.gov"+minutesUrl;m.sourceChannel="FED_CALENDAR";m.sourceAsOf=received;m.status="RELEASED";m.actualBasis="OFFICIAL_RELEASE";m.validate();b.events.add(m);}
            }
        }
        if(b.events.isEmpty())throw CalendarData.bad("FOMC 官方页面结构或会议字段不可识别");return b;
    }
    public static Batch fedRss(String raw,Instant received,List<CalendarData> schedule)throws Exception{
        Batch b=new Batch();NodeList items=xml(raw).getElementsByTagName("item");for(int i=0;i<items.getLength();i++){Element item=(Element)items.item(i);String title=text(item,"title"),url=text(item,"link");
            boolean decision="Federal Reserve issues FOMC statement".equals(title),minutes=title!=null&&title.startsWith("Minutes of the Federal Open Market Committee,");if(!decision&&!minutes)continue;
            CalendarData d=CalendarData.metric(decision?"FOMC_DECISION":"FOMC_MINUTES");Instant time=rssDate(text(item,"pubDate"));if(decision){CalendarData known=schedule.stream().filter(e->e.metric.equals("FOMC_DECISION")&&Objects.equals(e.releaseDate,time.atZone(EASTERN).toLocalDate())).findFirst().orElse(null);if(known==null)continue;d.statisticalPeriod=known.statisticalPeriod;}else{String m=match("Committee, ([A-Za-z]+)",title,1),y=match("20[0-9]{2}",title,0);if(m==null||y==null)continue;d.statisticalPeriod=YearMonth.of(Integer.parseInt(y),month(m)).toString();}
            d.sourceUrl=url;d.sourceChannel="FED_DATA";d.sourceUid=text(item,"guid");d.sourceAsOf=received;d.actualBasis="OFFICIAL_RELEASE";d.releaseAt=time;d.timePrecision="MINUTE";d.status="RELEASED";d.validate();b.events.add(d);
        }
        if(b.events.isEmpty())throw CalendarData.bad("FOMC RSS 尚无可关联公告");return b;
    }
}
