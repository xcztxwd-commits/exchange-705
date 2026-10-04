package com.gtcfesk.exchange.insights.news;
import java.time.Instant;
/** Only allowed headline metadata; never article bodies, images or translations. */
public class NewsItem {
    public String articleId,sourceId,publisher,title,summary,originalUrl,language="en",category="MACRO",attribution;
    public Instant publishedAt,discoveredAt;
    public int delayHours;
}
