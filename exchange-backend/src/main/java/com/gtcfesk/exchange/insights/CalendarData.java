package com.gtcfesk.exchange.insights;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** One specific metric and release vintage, not an ambiguous headline value. */
public class CalendarData {
    public String eventId, title, agency, metric, statisticalPeriod, sourceUid;
    public String country="US", importance="HIGH", releaseStage="INITIAL";
    public Instant releaseAt, sourceAsOf;
    public LocalDate releaseDate;
    public String sourceTimezone="America/New_York", timePrecision="UNKNOWN", status="SCHEDULED";
    public String actual, previous, forecast, adminEstimate, estimateReason;
    public String unit, comparison, seasonality, sourceUrl, sourceChannel, actualBasis="NOT_PROVIDED", footnotes;
    public String forecastStatus="NOT_PROVIDED", estimateKind;
    public boolean isRevised, historicalInitialKnown;
    public String originalReleaseValue;
    public long sourceRevision;

    public static final List<String> METRICS=Collections.unmodifiableList(Arrays.asList("NFP_CHANGE","UNEMPLOYMENT_RATE","CPI_YOY","CORE_PCE_MOM","GDP_QOQ_ANNUALIZED","FOMC_DECISION","FOMC_MINUTES"));
    public static final List<String> STATES=Collections.unmodifiableList(Arrays.asList("SCHEDULED","AWAITING_RELEASE","RELEASED","POSTPONED","CANCELLED"));
    public static CalendarData metric(String metric) {
        if(!METRICS.contains(metric))throw bad("缺少或不支持的事件类型");
        CalendarData d=new CalendarData(); d.metric=metric;
        switch(metric){
            case "NFP_CHANGE":d.agency="BLS";d.title="非农就业人数 · 月度变动";d.unit="THOUSAND_PERSONS";d.comparison="MONTHLY_CHANGE";d.seasonality="SA";break;
            case "UNEMPLOYMENT_RATE":d.agency="BLS";d.title="失业率";d.unit="PERCENT";d.comparison="LEVEL";d.seasonality="SA";break;
            case "CPI_YOY":d.agency="BLS";d.title="CPI · 同比";d.unit="PERCENT";d.comparison="YOY";d.seasonality="NSA";break;
            case "CORE_PCE_MOM":d.agency="BEA";d.title="核心 PCE 价格指数 · 环比";d.unit="PERCENT";d.comparison="MOM";d.seasonality="SA";break;
            case "GDP_QOQ_ANNUALIZED":d.agency="BEA";d.title="GDP · 实际季度环比年化";d.unit="PERCENT";d.comparison="QOQ_ANNUALIZED";d.seasonality="SA";break;
            case "FOMC_DECISION":case "FOMC_MINUTES":d.agency="FED";d.title=metric.equals("FOMC_DECISION")?"FOMC 决定":"FOMC 会议纪要";d.unit="NONE";d.comparison="POLICY_ANNOUNCEMENT";d.seasonality="NOT_APPLICABLE";d.releaseStage="MEETING";break;
            default:throw bad("不支持的事件类型");
        }
        return d;
    }
    public void validate() {
        CalendarData definition=metric(metric);
        if(!Objects.equals(agency,definition.agency)||!Objects.equals(unit,definition.unit)||!Objects.equals(comparison,definition.comparison)||!Objects.equals(seasonality,definition.seasonality))throw bad("指标、单位、比较口径或季调不匹配");
        if(!"US".equals(country)||!"HIGH".equals(importance)||!STATES.contains(status)||title==null||title.trim().isEmpty()||title.length()>160||title.matches("(?s).*[<>\\r\\n].*"))throw bad("事件字段无效");
        if(statisticalPeriod==null||statisticalPeriod.length()>180||!statisticalPeriod.matches("(20[0-9]{2}-(0[1-9]|1[0-2]|Q[1-4])|UID:[A-Za-z0-9@._:/-]{1,160})"))throw bad("统计期无效；未知统计期需官方 UID");
        if(!statisticalPeriod.startsWith("UID:")&&(metric.equals("GDP_QOQ_ANNUALIZED")?!statisticalPeriod.matches("20[0-9]{2}-Q[1-4]"):!statisticalPeriod.matches("20[0-9]{2}-(0[1-9]|1[0-2])")))throw bad("统计期与指标的月/季度口径不匹配");
        if(sourceUid!=null&&sourceUid.length()>200||sourceChannel!=null&&sourceChannel.length()>80)throw bad("来源身份字段过长");
        if(!Arrays.asList("INITIAL","INITIAL_ESTIMATE","ADVANCE","SECOND","THIRD","UPDATED","MEETING").contains(releaseStage)||sourceRevision<0)throw bad("发布阶段无效");
        if(metric.equals("GDP_QOQ_ANNUALIZED")&&!Arrays.asList("INITIAL_ESTIMATE","ADVANCE","SECOND","THIRD","UPDATED").contains(releaseStage))throw bad("GDP 必须标明有效估计阶段");
        if(!metric.equals("GDP_QOQ_ANNUALIZED")&&!metric.startsWith("FOMC")&&!releaseStage.equals("INITIAL"))throw bad("月度统计事件修订保留 INITIAL 身份");
        if(metric.startsWith("FOMC")&&!releaseStage.equals("MEETING"))throw bad("政策公告发布阶段无效");
        try{ZoneId.of(sourceTimezone);}catch(Exception e){throw bad("来源时区无效");}
        if(!Arrays.asList("MINUTE","DATE","UNKNOWN").contains(timePrecision))throw bad("时间精度无效");
        if("MINUTE".equals(timePrecision)){
            if(releaseAt==null||releaseAt.getEpochSecond()%60!=0||releaseAt.getNano()!=0)throw bad("分钟精度须提供整分钟 UTC 时间");
            LocalDate expected=releaseAt.atZone(ZoneId.of(sourceTimezone)).toLocalDate();
            if(releaseDate!=null&&!releaseDate.equals(expected))throw bad("来源日期和 UTC 时间不一致"); releaseDate=expected;
        }else if(releaseAt!=null||("DATE".equals(timePrecision)&&releaseDate==null)||("UNKNOWN".equals(timePrecision)&&releaseDate!=null))throw bad("日期/未知精度不能伪装成确定时刻");
        if(releaseDate!=null&&(releaseDate.getYear()<2000||releaseDate.getYear()>2100))throw bad("发布日期超出范围");
        number(actual);number(previous);number(adminEstimate);number(originalReleaseValue);
        if("NONE".equals(unit)&&(actual!=null||previous!=null||adminEstimate!=null))throw bad("政策公告未映射利率，不能填数字");
        if(metric.equals("UNEMPLOYMENT_RATE")){for(String value:Arrays.asList(actual,previous,adminEstimate))if(value!=null&&(new BigDecimal(value).signum()<0||new BigDecimal(value).compareTo(new BigDecimal("100"))>0))throw bad("失业率范围无效");}
        if(forecast!=null||!"NOT_PROVIDED".equals(forecastStatus))throw bad("未提供合法共识来源，forecast 必须为空");
        if(adminEstimate!=null){if(estimateReason==null||estimateReason.trim().isEmpty()||estimateReason.length()>1000)throw bad("管理员估计须说明依据");estimateKind="ADMIN_ESTIMATE";}else estimateKind=null;
        if(footnotes!=null&&footnotes.length()>2000)throw bad("脚注过长");
        if(!Arrays.asList("NOT_PROVIDED","LATEST_TIME_SERIES","OFFICIAL_RELEASE","OFFICIAL_MATERIAL").contains(actualBasis))throw bad("实际值来源口径无效");
        if((actual!=null||previous!=null)&&"NOT_PROVIDED".equals(actualBasis))throw bad("数值必须提供真实来源口径");
        if("LATEST_TIME_SERIES".equals(actualBasis)){originalReleaseValue=null;historicalInitialKnown=false;}
        if(historicalInitialKnown!=(originalReleaseValue!=null))throw bad("历史初值与已核验标志须同时提供");
        officialUrl(sourceUrl,agency);
        String stable=UUID.nameUUIDFromBytes((agency+"|"+metric+"|"+statisticalPeriod+"|"+releaseStage).getBytes(StandardCharsets.UTF_8)).toString();
        if(eventId!=null&&!eventId.equals(stable))throw bad("事件身份不能修改");eventId=stable;
    }
    public static void number(String value){if(value==null)return;if(!value.matches("-?[0-9]{1,14}(\\.[0-9]{1,6})?"))throw bad("数值须为有限十进制字符串（最多6位小数）");}
    public static void officialUrl(String url,String agency){
        try{URI u=new URI(url);String host=u.getHost();List<String> hosts="BLS".equals(agency)?Arrays.asList("www.bls.gov","api.bls.gov"):"BEA".equals(agency)?Arrays.asList("www.bea.gov","bea.gov","apps.bea.gov"):Arrays.asList("www.federalreserve.gov","federalreserve.gov");
            if(url.length()>800||!"https".equals(u.getScheme())||u.getUserInfo()!=null||u.getPort()!=-1||u.getFragment()!=null||!hosts.contains(host)||url.matches("(?s).*[\\r\\n].*"))throw new IllegalArgumentException();
        }catch(Exception e){throw bad("只允许对应机构官方 HTTPS 材料链接");}
    }
    public static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
