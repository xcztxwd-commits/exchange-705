package com.gtcfesk.exchange.insights.news;
import com.fasterxml.jackson.databind.*;import org.w3c.dom.*;
import javax.xml.parsers.*;import javax.xml.XMLConstants;
import javax.swing.text.html.*;import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.time.format.*;import java.util.*;
public final class NewsParser {
    private NewsParser(){}
    public static class Batch {public List<NewsItem> items=new ArrayList<>();public int skipped;}
    public static String plain(String html,int limit){
        if(html==null||html.trim().isEmpty())return null;
        // Swing's HTML parser can expose STYLE text in malformed fragments; remove blocked containers first.
        html=html.replaceAll("(?is)<(script|style|iframe|object|noscript)\\b[^>]*>.*?</\\1\\s*>"," ").replaceAll("(?is)<(script|style|iframe|object|noscript)\\b[^>]*>.*$"," ");
        StringBuilder out=new StringBuilder();
        try{new ParserDelegator().parse(new StringReader(html),new HTMLEditorKit.ParserCallback(){int blocked;
            public void handleStartTag(HTML.Tag t,javax.swing.text.MutableAttributeSet a,int p){if(t==HTML.Tag.SCRIPT||t==HTML.Tag.STYLE)blocked++;else out.append(' ');}
            public void handleEndTag(HTML.Tag t,int p){if(t==HTML.Tag.SCRIPT||t==HTML.Tag.STYLE)blocked=Math.max(0,blocked-1);else out.append(' ');}
            public void handleText(char[] data,int p){if(blocked==0)out.append(data).append(' ');}
        },true);}catch(IOException e){throw NewsCatalog.bad("内容无法解析");}
        String s=out.toString().replaceAll("[\\p{Cc}\\p{Cf}]"," ").replaceAll("\\s+"," ").trim();return s.isEmpty()?null:s.substring(0,Math.min(limit,s.length()));
    }
    public static String url(String raw){
        try{if(raw==null||raw.length()>2048||raw.matches(".*[\\p{Cc}\\p{Cf}\\\\].*")||!raw.equals(raw.trim()))throw new Exception();URI u=new URI(raw);String scheme=u.getScheme(),host=u.getHost();
            if(!Arrays.asList("http","https").contains(scheme)||host==null||u.getRawUserInfo()!=null||host.contains(":")||host.matches("[0-9.]+")||!host.contains(".")||host.toLowerCase(Locale.ROOT).matches(".*\\.(local|localhost|internal|invalid|test)")||u.getPort()!=-1&&u.getPort()!=(scheme.equals("https")?443:80))throw new Exception();
            host=IDN.toASCII(host).toLowerCase(Locale.ROOT);String path=u.getRawPath();if(path==null||path.isEmpty())path="/";path=path.replaceAll("/{2,}","/");List<String> query=new ArrayList<>();if(u.getRawQuery()!=null)for(String pair:u.getRawQuery().split("&")){String name=URLDecoder.decode(pair.split("=",2)[0],"UTF-8").toLowerCase(Locale.ROOT);if(!name.startsWith("utm_")&&!Arrays.asList("fbclid","gclid").contains(name))query.add(pair);}Collections.sort(query);
            return new URI(scheme+"://"+host+path+(query.isEmpty()?"":"?"+String.join("&",query))).normalize().toASCIIString();
        }catch(Exception e){throw NewsCatalog.bad("原文须为正常公共http(s)链接，不允许内网、IP、凭证或危险协议");}
    }
    public static Instant date(String raw,Instant now){if(raw==null)return null;Instant out=null;try{out=Instant.parse(raw.trim());}catch(Exception a){try{out=OffsetDateTime.parse(raw.trim()).toInstant();}catch(Exception b){for(DateTimeFormatter f:Arrays.asList(DateTimeFormatter.RFC_1123_DATE_TIME,DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z",Locale.US))){try{out=ZonedDateTime.parse(raw.trim(),f).toInstant();break;}catch(Exception ignored){}}}}return out!=null&&(out.isBefore(Instant.parse("1900-01-01T00:00:00Z"))||out.isAfter(now.plusSeconds(86400)))?null:out;}
    private static String child(Element e,String name){for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&(name.equals(n.getLocalName())||name.equals(n.getNodeName())))return n.getTextContent();return null;}
    private static boolean allowed(String source,String original){URI u=URI.create(original);String host=u.getHost(),path=u.getPath();if(source.equals("FED"))return host.equals("www.federalreserve.gov")&&path.startsWith("/newsevents/pressreleases/");if(source.equals("BEA"))return host.equals("www.bea.gov")&&path.startsWith("/news/");if(source.equals("ECB"))return host.equals("www.ecb.europa.eu")&&(path.startsWith("/press/pr/date/")||path.startsWith("/press/govcdec/"));return true;}
    private static NewsItem item(String source,String title,String link,String description,String published,String publisher,String language,Instant now){
        NewsItem n=new NewsItem();n.sourceId=source;n.title=plain(title,500);n.originalUrl=url(link);if(n.title==null||!allowed(source,n.originalUrl))throw NewsCatalog.bad("条目不在允许的官方新闻内容范围");n.articleId=UUID.nameUUIDFromBytes((source+"\n"+n.originalUrl).getBytes(StandardCharsets.UTF_8)).toString();n.discoveredAt=now;n.publishedAt=date(published,now);n.publisher=publisher==null?NewsCatalog.NAMES.get(source):plain(publisher,150);n.attribution="Source: "+n.publisher+(source.equals("GDELT")?"; discovered via GDELT. Publisher rights retained.":source.equals("ECB")?"; freely available at ecb.europa.eu; headline only, no framing or endorsement.":"; no endorsement.");
        n.summary=Arrays.asList("FED","BEA").contains(source)?plain(description,500):null;n.category=NewsCatalog.official(source)?"MACRO":"CRYPTO";n.language=language==null?"en":language(language);n.delayHours=source.equals("NEWSDATA")?12:0;return n;
    }
    public static String language(String v){if("English".equalsIgnoreCase(v))return "en";if(v==null)return "und";String s=v.toLowerCase(Locale.ROOT);return s.matches("[a-z]{2,8}(-[a-z0-9]{1,8})*")&&s.length()<=16?s:"und";}
    public static Batch xml(String source,String body,Instant now)throws Exception {
        if(!NewsCatalog.official(source))throw NewsCatalog.bad("仅支持白名单官方RSS/Atom材料");if(body==null||body.getBytes(StandardCharsets.UTF_8).length>2000000)throw NewsCatalog.bad("RSS超过2MB");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");DocumentBuilder builder=f.newDocumentBuilder();builder.setEntityResolver((p,s)->{throw new org.xml.sax.SAXException("External entities disabled");});Document d=builder.parse(new org.xml.sax.InputSource(new StringReader(body.replaceFirst("^\\uFEFF",""))));Element root=d.getDocumentElement();String name=root.getLocalName();if(!Arrays.asList("rss","feed","RDF").contains(name))throw NewsCatalog.bad("须为RSS或Atom");NodeList entries=d.getElementsByTagNameNS("*",name.equals("feed")?"entry":"item");if(entries.getLength()>500)throw NewsCatalog.bad("RSS条目超过500条");Batch b=new Batch();Map<String,NewsItem> unique=new LinkedHashMap<>();
        for(int i=0;i<entries.getLength();i++){Element e=(Element)entries.item(i);String link=child(e,"link");if(name.equals("feed")){NodeList links=e.getElementsByTagNameNS("*","link");link=null;for(int j=0;j<links.getLength();j++){Element l=(Element)links.item(j);if(!l.hasAttribute("rel")||l.getAttribute("rel").equals("alternate")){link=l.getAttribute("href");break;}}}
            String author=child(e,"author"),rights=child(e,"rights");if(author!=null||child(e,"creator")!=null||rights!=null&&rights.toLowerCase(Locale.ROOT).contains("copyright")){b.skipped++;continue;}
            try{NewsItem n=item(source,child(e,"title"),link,child(e,"description")!=null?child(e,"description"):child(e,"summary"),child(e,"pubDate")!=null?child(e,"pubDate"):child(e,"published"),null,"en",now);if(unique.putIfAbsent(n.articleId,n)!=null)b.skipped++;}catch(org.springframework.web.server.ResponseStatusException ex){b.skipped++;}
        }b.items.addAll(unique.values());return b;
    }
    public static Batch index(String source,String body,Instant now,ObjectMapper json)throws Exception {
        if(body==null||body.getBytes(StandardCharsets.UTF_8).length>2000000)throw NewsCatalog.bad("索引响应超过2MB");JsonNode root=json.readTree(body);if(root==null)throw NewsCatalog.bad("空响应");if(source.equals("NEWSDATA")&&!root.path("status").asText().equals("success"))throw NewsCatalog.bad("NewsData业务响应失败");JsonNode rows=root.get(source.equals("GDELT")?"articles":"results");if(rows==null||!rows.isArray()||rows.size()>100)throw NewsCatalog.bad("索引列表无效或超限");Batch b=new Batch();Set<String> seen=new HashSet<>();for(JsonNode row:rows)try{String link=row.path(source.equals("GDELT")?"url":"link").asText(null),published=source.equals("GDELT")?null:row.path("pubDate").asText(null);NewsItem n=item(source,row.path("title").asText(null),link,null,published,row.path(source.equals("GDELT")?"domain":"source_name").asText(URI.create(url(link)).getHost()),row.path("language").asText("en"),now);if(seen.add(n.articleId))b.items.add(n);else b.skipped++;}catch(Exception ex){b.skipped++;}return b;
    }
}
