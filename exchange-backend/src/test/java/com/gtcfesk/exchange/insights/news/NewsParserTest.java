package com.gtcfesk.exchange.insights.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class NewsParserTest {
    public static final Instant NOW=Instant.parse("2026-10-03T08:00:00Z");
    public static String raw(String source)throws Exception {
        try(java.io.InputStream in=NewsParserTest.class.getResourceAsStream("/news-official-20261003/"+source.toLowerCase(Locale.ROOT)+".xml")){
            assertNotNull(in);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    public static String rss(String title,String url,String date){return "<rss version='2.0'><channel><item><title>"+title+"</title><link>"+url+"</link>"+(date==null?"":"<pubDate>"+date+"</pubDate>")+"</item></channel></rss>";}
    @Test void capturedOfficialRssAndConservativeEcbScope()throws Exception {
        assertEquals(15,NewsParser.xml("FED",raw("FED"),NOW).items.size());assertEquals(42,NewsParser.xml("BEA",raw("BEA"),NOW).items.size());assertEquals(7,NewsParser.xml("BEA",raw("BEA"),NOW).skipped);
        NewsParser.Batch ecb=NewsParser.xml("ECB",raw("ECB"),NOW);assertTrue(ecb.items.size()>0);assertTrue(ecb.skipped>0);
        for(NewsItem n:ecb.items){assertNull(n.summary);assertTrue(n.originalUrl.contains("/press/pr/date/")||n.originalUrl.contains("/press/govcdec/"));assertEquals("MACRO",n.category);}
    }
    @Test void atomAlternateLinkAndUnknownPublication()throws Exception {
        String atom="<feed xmlns='http://www.w3.org/2005/Atom' xml:lang='en'><entry><title>Official test metadata</title><link rel='self' href='https://example.invalid/feed'/><link rel='alternate' href='https://www.bea.gov/news/test-atom'/><updated>2026-10-02T08:00:00Z</updated><summary>Allowed test summary</summary></entry></feed>";
        NewsItem n=NewsParser.xml("BEA",atom,NOW).items.get(0);assertNull(n.publishedAt);assertEquals(NOW,n.discoveredAt);assertEquals("Allowed test summary",n.summary);assertEquals("en",n.language);
    }
    @Test void normalizationDeduplicatesWithinSourceNotAcrossPublishers()throws Exception {
        String a=NewsParser.url("https://www.bea.gov//news/example?utm_source=x&amp=".replace("&amp=","&b=2&a=1#section"));
        assertEquals("https://www.bea.gov/news/example?a=1&b=2",a);
        String body="<rss><channel><item><title>One</title><link>https://www.bea.gov/news/test?utm_source=x</link></item><item><title>One again</title><link>https://www.bea.gov/news/test#ref</link></item></channel></rss>";
        NewsParser.Batch b=NewsParser.xml("BEA",body,NOW);assertEquals(1,b.items.size());assertEquals(1,b.skipped);
        String index="{\"articles\":[{\"title\":\"One\",\"url\":\"https://www.bea.gov/news/test\",\"domain\":\"bea.gov\"}]}";
        assertNotEquals(b.items.get(0).articleId,NewsParser.index("GDELT",index,NOW,new ObjectMapper()).items.get(0).articleId);
    }
    @Test void plainTextNeverCarriesScriptStylesImagesOrMarkup(){String s=NewsParser.plain("<p>Policy &amp; growth</p><script>TEST_SCRIPT_MARKER</script><style>TEST_STYLE_MARKER</style><img src='unused'><b>Facts</b>",500);assertTrue(s.contains("Policy & growth"));assertFalse(s.contains("TEST_SCRIPT"));assertFalse(s.contains("TEST_STYLE"));assertFalse(s.contains("<"));assertNull(NewsParser.plain("<script>only blocked text</script>",500));assertEquals(5,NewsParser.plain("1234567890",5).length());}
    @Test void rejectsNonPublicAndNonHttpLinks(){for(String url:Arrays.asList("javascript:invalid","file:///unused","https://127.0.0.1/","http://localhost/","https://service.internal/a","https://example.test/","https://user:pass@example.com/","https://example.com:8443/a","https://example.com/\\bad"," https://example.com/"))assertThrows(ResponseStatusException.class,()->NewsParser.url(url),url);}
    @Test void rejectsDtdAndOversizedOrNonFeedXml(){assertThrows(Exception.class,()->NewsParser.xml("FED","<!DOCTYPE rss><rss><channel/></rss>",NOW));assertThrows(Exception.class,()->NewsParser.xml("FED","<html/>",NOW));assertThrows(Exception.class,()->NewsParser.xml("FED",String.join("",Collections.nCopies(2000001,"x")),NOW));assertThrows(Exception.class,()->NewsParser.xml("GDELT","<rss/>",NOW));}
    @Test void capsEntryCountAndKeepsCopyrightExceptionsOut()throws Exception {
        String item="<item><title>Test</title><link>https://www.bea.gov/news/test</link></item>";
        assertThrows(Exception.class,()->NewsParser.xml("BEA","<rss><channel>"+String.join("",Collections.nCopies(501,item))+"</channel></rss>",NOW));
        String body="<rss><channel><item><author>Named author</author><title>Authored</title><link>https://www.bea.gov/news/test</link></item><item><rights>copyright third party</rights><title>Protected</title><link>https://www.bea.gov/news/test2</link></item></channel></rss>";
        NewsParser.Batch b=NewsParser.xml("BEA",body,NOW);assertTrue(b.items.isEmpty());assertEquals(2,b.skipped);
    }
    @Test void datesKeepUnknownAndValidateRange(){assertNull(NewsParser.date(null,NOW));assertNull(NewsParser.date("not a date",NOW));assertNull(NewsParser.date("1890-01-01T00:00:00Z",NOW));assertNull(NewsParser.date("2099-01-01T00:00:00Z",NOW));assertEquals(Instant.parse("2026-09-30T12:31:00Z"),NewsParser.date("Wed, 30 Sep 2026 08:31:00 EDT",NOW));assertEquals(Instant.parse("2026-10-02T13:00:00Z"),NewsParser.date("Fri, 02 Oct 2026 15:00:00 +0200",NOW));}
    @Test void gdeltDiscoveryNeverBecomesPublicationAndNewsdataBusinessStatus()throws Exception {
        String index="{\"articles\":[{\"title\":\"Test index headline\",\"url\":\"https://example.com/news\",\"domain\":\"example.com\",\"seendate\":\"20261001T120000Z\",\"language\":\"English\"}]}";
        NewsItem n=NewsParser.index("GDELT",index,NOW,new ObjectMapper()).items.get(0);assertNull(n.publishedAt);assertEquals(NOW,n.discoveredAt);assertNull(n.summary);assertEquals("CRYPTO",n.category);assertEquals("en",n.language);
        assertThrows(Exception.class,()->NewsParser.index("NEWSDATA","{\"status\":\"error\",\"results\":[]}",NOW,new ObjectMapper()));
        NewsItem delayed=NewsParser.index("NEWSDATA","{\"status\":\"success\",\"results\":[{\"title\":\"Test only\",\"link\":\"https://example.com/news2\",\"pubDate\":\"2026-10-02T12:00:00Z\",\"source_name\":\"Example\",\"language\":\"en\"}]}",NOW,new ObjectMapper()).items.get(0);assertEquals(12,delayed.delayHours);assertNull(delayed.summary);
    }
    @Test void retryAfterSupportsSecondsDateAndBounds(){assertEquals(NOW.plusSeconds(7200),NewsSync.retryAfter("7200",NOW));assertEquals(NOW.plusSeconds(604800),NewsSync.retryAfter("999999999",NOW));assertEquals(Instant.parse("2026-10-03T10:00:00Z"),NewsSync.retryAfter("Sat, 03 Oct 2026 10:00:00 GMT",NOW));assertNull(NewsSync.retryAfter("invalid",NOW));}
}
