package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DepthPushTest {
    MarketWebSocketHandler handler;MarketDepthService depth;WebSocketSession session;
    final List<com.fasterxml.jackson.databind.JsonNode> received=new CopyOnWriteArrayList<>();
    @BeforeEach void setup()throws Exception{
        handler=new MarketWebSocketHandler();depth=mock(MarketDepthService.class);ReflectionTestUtils.setField(handler,"depth",depth);
        ForexQuoteMarketService prices=mock(ForexQuoteMarketService.class);ReflectionTestUtils.setField(handler,"marketService",prices);when(prices.knownSymbol("BTCUSDT")).thenReturn(true);Map<String,Object> quote=new HashMap<>();quote.put("price",100);quote.put("quoteVersion",1);when(prices.snapshotPrice("BTCUSDT")).thenReturn(quote);
        when(prices.readSnapshot(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        TenantRepository tenants=mock(TenantRepository.class);Tenant tenant=new Tenant();tenant.setId(1L);tenant.setFrontendHost("a.test");tenant.setDomainVerified(true);tenant.setStatus("ACTIVE");when(tenants.findById(1L)).thenReturn(Optional.of(tenant));ReflectionTestUtils.setField(handler,"tenants",tenants);
        session=mock(WebSocketSession.class);when(session.getId()).thenReturn("depth-test");when(session.isOpen()).thenReturn(true);Map<String,Object> attributes=new HashMap<>();attributes.put("tenantId",1L);attributes.put("frontendHost","a.test");when(session.getAttributes()).thenReturn(attributes);
        doAnswer(i->{received.add(ExchangeQuoteSource.JSON.readTree(((TextMessage)i.getArgument(0)).getPayload()));return null;}).when(session).sendMessage(any());
        when(depth.read(anyString(),anyInt(),nullable(String.class),eq("ws:depth-test"))).thenAnswer(i->{assertEquals(1L,TenantContext.requireTenantId());Map<String,Object> result=new HashMap<>();result.put("symbol",i.getArgument(0));result.put("status","LIVE");return result;});handler.afterConnectionEstablished(session);
    }
    @AfterEach void stop(){handler.destroy();}
    void subscribe(String symbol)throws Exception{handler.handleTextMessage(session,new TextMessage("{\"action\":\"subscribeDepth\",\"symbol\":\""+symbol+"\"}"));}
    @Test void independentDepthDoesNotStarvePriceAndRemainsOnePerSecond()throws Exception{
        handler.handleTextMessage(session,new TextMessage("{\"action\":\"subscribe\",\"symbols\":[\"BTCUSDT\"],\"fastSymbols\":[\"BTCUSDT\"]}"));
        ((ScheduledExecutorService)ReflectionTestUtils.getField(handler,"scheduler")).submit(()->{}).get();Thread.sleep(100);received.clear();subscribe("BTCUSDT");handler.push();MarketDepthTest.until(()->received.size()>=2);
        assertTrue(received.stream().anyMatch(n->"depth".equals(n.path("type").asText())));assertTrue(received.stream().anyMatch(n->"price".equals(n.path("type").asText())));
        assertTrue(received.stream().noneMatch(n->n.has("depthRevision")));for(int i=0;i<5;i++){handler.push();Thread.sleep(30);}assertEquals(1,received.stream().filter(n->"depth".equals(n.path("type").asText())).count());
    }
    @Test void queuedOldTargetDroppedSwitchAndDisconnectRelease()throws Exception{
        ThreadPoolExecutor senders=(ThreadPoolExecutor)ReflectionTestUtils.getField(handler,"senders");CountDownLatch busy=new CountDownLatch(4),release=new CountDownLatch(1);
        for(int i=0;i<4;i++)senders.execute(()->{busy.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}});assertTrue(busy.await(2,TimeUnit.SECONDS));
        subscribe("BTCUSDT");handler.push();subscribe("ETHUSDT");release.countDown();MarketDepthTest.until(()->senders.getActiveCount()==0&&senders.getQueue().isEmpty());assertTrue(received.isEmpty(),"queued former-target frame must be dropped");
        handler.push();MarketDepthTest.until(()->received.size()==1);assertEquals("ETHUSDT",received.get(0).path("data").path("symbol").asText());
        handler.afterConnectionClosed(session,CloseStatus.NORMAL);verify(depth,atLeast(3)).release("ws:depth-test");
    }
    @Test void slowClientNeverAccumulatesFramesAndIsClosed()throws Exception{
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);doAnswer(i->{blocked.countDown();release.await(3,TimeUnit.SECONDS);return null;}).when(session).sendMessage(any());
        subscribe("BTCUSDT");handler.push();assertTrue(blocked.await(1,TimeUnit.SECONDS));for(int i=0;i<100;i++)handler.push();ThreadPoolExecutor senders=(ThreadPoolExecutor)ReflectionTestUtils.getField(handler,"senders");assertEquals(0,senders.getQueue().size());
        Object client=((Map<?,?>)ReflectionTestUtils.getField(handler,"clients")).get(session);ReflectionTestUtils.setField(client,"busySince",System.currentTimeMillis()-11000);handler.push();verify(session).close(CloseStatus.SESSION_NOT_RELIABLE);verify(depth,atLeastOnce()).release("ws:depth-test");release.countDown();
    }
}
