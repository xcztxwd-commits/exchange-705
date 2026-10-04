package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.common.BusinessException;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Local execution calendar, not a promise that a provider has an executable quote. */
public final class MarketHoursConfig {
    public static final String KEY = "market.hours.v1";
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    public static class Settings {
        public int version = 1;
        public long revision;
        public List<Strategy> strategies = new ArrayList<>();
        public Map<String, Rule> categories = new LinkedHashMap<>();
        public Map<String, Rule> symbols = new LinkedHashMap<>();
    }
    public static class Strategy {
        public String id, name, timezone = "America/New_York", note = "";
        public List<Weekly> weekly = new ArrayList<>();
        public List<ExceptionRange> exceptions = new ArrayList<>();
    }
    public static class Weekly {
        public int startDay, endDay;
        public String startTime, endTime, reason, timezone;
        public Weekly() {}
        Weekly(int startDay, String startTime, int endDay, String endTime, String reason) {
            this.startDay=startDay; this.startTime=startTime; this.endDay=endDay; this.endTime=endTime; this.reason=reason;
        }
    }
    public static class ExceptionRange {
        public String start, end, reason, source;
        public boolean closed = true;
    }
    public static class Rule {
        public String strategyId, mode = "AUTO", until, reason = "";
    }
    public static class Status {
        public boolean closed;
        public String reason, strategyId, mode = "AUTO";
        public Long nextChangeAt;
        public long revision;
    }
    private static class Range {
        final Instant start, end; final boolean closed; final String reason;
        Range(Instant start, Instant end, boolean closed, String reason) {this.start=start;this.end=end;this.closed=closed;this.reason=reason;}
        boolean contains(Instant now) {return !now.isBefore(start) && now.isBefore(end);}
    }
    private MarketHoursConfig() {}
    public static Settings defaults() {
        Settings s = new Settings();
        Strategy standard = strategy("fx-weekend", "外汇通用周末（参考）", "参考 24×5 周交易窗口；执行源未确定前使用，非全球统一成交时间。节假日须按执行商当年公告添加例外。");
        standard.weekly.add(new Weekly(5,"17:00",7,"17:00","外汇周末休市"));
        Strategy oanda = strategy("fx-oanda", "外汇 OANDA US（示例）", "OANDA US 常规时段：周日17:05至周五16:59，每日16:59–17:05暂停；TRY、NZD有额外差异，须品种覆盖。核验日期2026-10-03。");
        oanda.weekly.add(new Weekly(5,"16:59",7,"17:05","外汇周末休市"));
        for(int d=1;d<=4;d++) oanda.weekly.add(new Weekly(d,"16:59",d,"17:05","执行商日切维护"));
        ExceptionRange newYear=new ExceptionRange();newYear.start="2025-12-31T21:59:00Z";newYear.end="2026-01-01T22:05:00Z";
        newYear.reason="OANDA US 2026元旦公告";newYear.source="https://www.oanda.com/us-en/trading/holiday-trading-hours/";oanda.exceptions.add(newYear);
        Strategy always = strategy("always-open", "全天候（无计划休市）", "仅适用于24×7资产或经确认的自定义时段；仍须有效行情。");
        always.timezone="UTC";
        s.strategies.add(standard);s.strategies.add(oanda);s.strategies.add(always);
        Strategy nzd=strategy("fx-oanda-nzd","OANDA US NZD（示例）","纽约常规时段叠加奥克兰06:59–07:05暂停；只在实际执行商使用此安排时绑定NZD品种。核验2026-10-03。");
        nzd.weekly.addAll(oanda.weekly);nzd.exceptions.add(newYear);
        for(int d=1;d<=7;d++){Weekly w=new Weekly(d,"06:59",d,"07:05","NZD额外日切维护");w.timezone="Pacific/Auckland";nzd.weekly.add(w);}
        s.strategies.add(nzd);
        Strategy tryPairs=strategy("fx-oanda-try","OANDA US TRY（示例）","中欧周一至周五08:00–17:00交易；Europe/Berlin按中欧时区处理夏令时。仅执行商核验一致后绑定TRY品种。");
        tryPairs.timezone="Europe/Berlin";tryPairs.weekly.add(new Weekly(5,"17:00",1,"08:00","TRY周末休市"));
        for(int d=1;d<=4;d++)tryPairs.weekly.add(new Weekly(d,"17:00",d+1,"08:00","TRY非交易时段"));
        tryPairs.exceptions.add(newYear);s.strategies.add(tryPairs);
        Rule forex = new Rule();forex.strategyId=standard.id;s.categories.put("Forex",forex);
        return s;
    }
    private static Strategy strategy(String id,String name,String note) {Strategy p=new Strategy();p.id=id;p.name=name;p.note=note;return p;}
    public static Settings parse(String raw) {
        if(raw==null) return defaults();
        try {
            if(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000) throw bad("配置超过60KB");
            Settings result=JSON.readValue(raw,Settings.class);validate(result);return result;
        } catch(java.io.IOException e) {throw bad("配置格式无效");}
    }
    public static String encode(Settings s) {
        validate(s);
        try {String raw=JSON.writeValueAsString(s);if(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000)throw bad("配置超过60KB");return raw;}
        catch(java.io.IOException e) {throw bad("配置序列化失败");}
    }
    public static void validate(Settings s) {
        if(s==null||s.version!=1||s.revision<0||s.strategies==null||s.strategies.isEmpty()||s.strategies.size()>30
                ||s.categories==null||s.categories.size()>30||s.symbols==null||s.symbols.size()>300)throw bad("配置结构或数量无效");
        Set<String> ids=new HashSet<>();
        for(Strategy p:s.strategies) {
            if(p==null||p.id==null||!p.id.matches("[a-z][a-z0-9-]{0,47}")||!ids.add(p.id))throw bad("策略编号无效或重复");
            text(p.name,80);if(p.note==null||p.note.length()>1000)throw bad("策略说明过长");
            try {ZoneId.of(p.timezone);}catch(RuntimeException e){throw bad("请使用有效IANA时区");}
            if(p.weekly==null||p.weekly.size()>64||p.exceptions==null||p.exceptions.size()>128)throw bad("时段数量无效");
            for(Weekly w:p.weekly) {
                if(w==null||w.startDay<1||w.startDay>7||w.endDay<1||w.endDay>7)throw bad("星期须为1至7");
                if(w.timezone!=null&&!w.timezone.isEmpty())try{ZoneId.of(w.timezone);}catch(RuntimeException e){throw bad("每周区间时区无效");}
                int a=minute(w.startDay,w.startTime),b=minute(w.endDay,w.endTime);
                if(a==b)throw bad("每周休市开始与结束不能相同");text(w.reason,200);
            }
            List<ExceptionRange> sorted=new ArrayList<>(p.exceptions);
            for(ExceptionRange e:sorted) {
                if(e==null||!instant(e.start).isBefore(instant(e.end)))throw bad("例外结束必须晚于开始");
                text(e.reason,200);text(e.source,500);
            }
            sorted.sort(Comparator.comparing(e->instant(e.start)));
            for(int i=1;i<sorted.size();i++)if(instant(sorted.get(i).start).isBefore(instant(sorted.get(i-1).end)))throw bad("日期例外不能重叠");
        }
        // The fallback must exist even if project taxonomy changes or an instrument is moved.
        if(!ids.contains("fx-weekend"))throw bad("须保留外汇默认策略fx-weekend");
        for(Map.Entry<String,Rule> e:s.categories.entrySet()) {
            if(e.getKey()==null||!e.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,31}"))throw bad("分类编号无效");rule(e.getValue(),ids);
        }
        for(Map.Entry<String,Rule> e:s.symbols.entrySet()) {
            if(e.getKey()==null||!e.getKey().matches("[1-9][0-9]{0,18}"))throw bad("品种编号无效");rule(e.getValue(),ids);
        }
    }
    private static void rule(Rule r,Set<String> ids) {
        if(r==null||!Arrays.asList("AUTO","OPEN","CLOSED").contains(r.mode)||r.strategyId!=null&&!ids.contains(r.strategyId))throw bad("模式或策略引用无效");
        if(r.reason==null||r.reason.length()>200)throw bad("操作原因过长");
        if(!"AUTO".equals(r.mode))text(r.reason,200);
        if(r.until!=null)instant(r.until);
        if("OPEN".equals(r.mode)&&r.until==null)throw bad("手动开市必须设置失效时间");
    }
    private static void text(String value,int max) {if(value==null||value.trim().isEmpty()||value.length()>max)throw bad("名称、原因或依据为空或过长");}
    private static int minute(int day,String value) {
        if(value==null||!value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw bad("时间须为HH:mm");
        return (day-1)*1440+LocalTime.parse(value).getHour()*60+LocalTime.parse(value).getMinute();
    }
    static Instant instant(String text) {
        try {Instant value=Instant.parse(text);
            if(value.isBefore(Instant.parse("2000-01-01T00:00:00Z"))||!value.isBefore(Instant.parse("2101-01-01T00:00:00Z")))throw new IllegalArgumentException();
            return value;
        }catch(RuntimeException e){throw bad("日期须为2000至2100年间含UTC偏移的ISO时间");}
    }
    static BusinessException bad(String message) {return new BusinessException("休市设置："+message);}
    private static String mode(Rule r,Instant now) {return r==null||r.until!=null&&!now.isBefore(instant(r.until))?"AUTO":r.mode;}

    public static Status evaluate(Settings s, com.gtcfesk.exchange.entity.TradingSymbol symbol, Instant now) {
        Rule category=s.categories.get(symbol.getCategory()), own=s.symbols.get(String.valueOf(symbol.getId()));
        String id=own!=null&&own.strategyId!=null?own.strategyId:category==null?null:category.strategyId;
        if(id==null&&"Forex".equalsIgnoreCase(symbol.getSourceCategory()))id="fx-weekend";
        Strategy policy=null;for(Strategy p:s.strategies)if(p.id.equals(id)){policy=p;break;}
        List<Range> ranges=policy==null?Collections.emptyList():ranges(policy,now);
        Status result=decision(s,category,own,id,policy,now);
        SortedSet<Instant> changes=new TreeSet<>();
        for(Range r:ranges){changes.add(r.start);changes.add(r.end);}
        for(Rule r:Arrays.asList(category,own))if(r!=null&&r.until!=null)changes.add(instant(r.until));
        // A long holiday/manual closure can end after the current weekly search window.
        if(policy!=null)for(Instant change:new ArrayList<>(changes))if(change.isAfter(now.plusSeconds(14*86400L)))
            for(Range r:ranges(policy,change)){changes.add(r.start);changes.add(r.end);}
        for(Instant change:changes.tailSet(now.plusNanos(1))) {
            if(decision(s,category,own,id,policy,change).closed!=result.closed){result.nextChangeAt=change.toEpochMilli();break;}
        }
        return result;
    }
    private static Status decision(Settings s,Rule category,Rule own,String id,Strategy policy,Instant now) {
        Status out=new Status();out.revision=s.revision;out.strategyId=id;out.reason="交易时段";
        // Category emergency closure cannot be bypassed by a single-symbol forced open.
        Rule manual="CLOSED".equals(mode(category,now))?category:"CLOSED".equals(mode(own,now))?own
                :"OPEN".equals(mode(own,now))?own:"OPEN".equals(mode(category,now))?category:null;
        if(manual!=null){out.mode=manual.mode;out.closed="CLOSED".equals(manual.mode);out.reason=manual.reason;return out;}
        // Date exceptions override recurring weekly ranges, including explicit holiday openings.
        if(policy!=null) {
            for(ExceptionRange e:policy.exceptions)if(!now.isBefore(instant(e.start))&&now.isBefore(instant(e.end))){out.closed=e.closed;out.reason=e.reason;return out;}
            for(Range r:ranges(policy,now))if(r.contains(now)){out.closed=true;out.reason=r.reason;return out;}
        }
        return out;
    }
    private static List<Range> ranges(Strategy p,Instant now) {
        List<Range> result=new ArrayList<>();
        for(int week=-1;week<=2;week++)for(Weekly w:p.weekly) {
            ZoneId zone=ZoneId.of(w.timezone==null||w.timezone.isEmpty()?p.timezone:w.timezone);
            LocalDate monday=now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate start=monday.plusWeeks(week).plusDays(w.startDay-1);
            LocalDate end=monday.plusWeeks(week).plusDays(w.endDay-1);
            if(minute(w.endDay,w.endTime)<minute(w.startDay,w.startTime))end=end.plusWeeks(1);
            // Repeated DST hour: close on its first occurrence, reopen on its last occurrence.
            result.add(new Range(start.atTime(LocalTime.parse(w.startTime)).atZone(zone).toInstant(),
                    end.atTime(LocalTime.parse(w.endTime)).atZone(zone).withLaterOffsetAtOverlap().toInstant(),true,w.reason));
        }
        for(ExceptionRange e:p.exceptions)result.add(new Range(instant(e.start),instant(e.end),e.closed,e.reason));
        return result;
    }
}
