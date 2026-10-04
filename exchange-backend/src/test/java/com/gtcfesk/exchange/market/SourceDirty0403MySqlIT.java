package com.gtcfesk.exchange.market;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Real SOURCE acceptance on a fresh independently restored 0403 clone; no fixture DDL, cleanup or forged writer identity. */
class SourceDirty0403MySqlIT {
    private static final long TENANT=1;
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static long MINUTE_BASE;
    private static final AtomicLong IDS=new AtomicLong(System.currentTimeMillis()*1000);
    private static RecordingSource source;
    private static JdbcTemplate plain;
    private static Map<String,List<String>> originalRows;
    private static Map<String,Object> identity;
    private final List<Trace> traces=new CopyOnWriteArrayList<>();
    private TenantContext.Scope tenant;
    @BeforeAll static void certifiedFresh0403() throws Exception {
        Path settingsPath=Paths.get(System.getProperty("joint.source0403.fixture")).toAbsolutePath().normalize();
        com.fasterxml.jackson.databind.JsonNode settings=JSON.readTree(Files.readAllBytes(settingsPath));
        assertTrue(settings.path("url").asText().startsWith("jdbc:mysql://127.0.0.1:33318/"));
        assertEquals("docker",settings.path("fixtureTransport").asText(),"This owned acceptance never falls back to old64029 transport");
        source=new RecordingSource(DedicatedMysqlFixture.fromProperty("joint.source0403.fixture"));plain=new JdbcTemplate(source);
        identity=plain.queryForMap("SELECT @@server_uuid AS server_uuid,VERSION() AS version,DATABASE() AS database_name");
        MINUTE_BASE=plain.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",Long.class)/60000*60000-1002*60000L;
        assertTrue(String.valueOf(identity.get("version")).startsWith("5.7."));
        assertEquals(1,plain.queryForObject("SELECT COUNT(*) FROM tenant_schema_version WHERE version=2026100403 AND minimum_application_epoch=2026100403 AND business_activation_ready=0",Integer.class));
        assertEquals(9,plain.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name IN ('joint_s4_progress_insert','joint_s4_progress_update','joint_s4_progress_delete','joint_s4_minute_insert','joint_s4_minute_update','joint_s4_minute_delete','joint_source_dirty_insert','joint_source_dirty_update','joint_source_dirty_delete')",Integer.class));
        com.fasterxml.jackson.databind.JsonNode proof=JSON.readTree(Files.readAllBytes(Paths.get(settings.path("fixtureProof").asText())));
        com.fasterxml.jackson.databind.JsonNode seal=proof.path("certified_initial_state");assertTrue(seal.isObject());
        Path state=Paths.get(seal.path("path").asText()).toAbsolutePath().normalize();
        assertFalse(Files.isSymbolicLink(state));assertTrue(Files.isRegularFile(state,LinkOption.NOFOLLOW_LINKS));assertTrue(Files.size(state)<=2*1024*1024);
        assertEquals(seal.path("sha256").asText(),DedicatedMysqlFixture.hash(state),"Immutable certified all-column state changed");
        com.fasterxml.jackson.databind.JsonNode expected=JSON.readTree(Files.readAllBytes(state)).path("data");
        TransactionTemplate baseline=new TransactionTemplate(new DataSourceTransactionManager(source));baseline.setReadOnly(true);baseline.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        baseline.execute(status->{requireNativeBaselineFingerprint(plain,expected);return null;});
        originalRows=rows();assertEquals(110,originalRows.size());
        assertEquals(30,originalRows.values().stream().mapToInt(List::size).sum(),"Exact immutable0402 backup29 + actual0403 inactive receipt1; old Joint31 included two additional test tenants");
    }
    @BeforeEach void enterTenant(){tenant=TenantContext.open(TENANT);}
    @AfterEach void retainEveryOriginalRowAndRawEvidence(TestInfo test) throws Exception {
        try {
            source.clear();Map<String,List<String>> after=rows();List<String> changed=new ArrayList<>();
            for(Map.Entry<String,List<String>> entry:originalRows.entrySet()){
                List<String> remaining=new ArrayList<>(after.getOrDefault(entry.getKey(),Collections.emptyList()));
                for(String row:entry.getValue())if(!remaining.remove(row)){changed.add(entry.getKey());break;}
            }
            Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("identity",identity);evidence.put("test",test.getDisplayName());
            evidence.put("qualification","Actual0403 MySQL SOURCE writer/storage statements if this prepared test is executed; no REST/WS/old83/84/full funded-service acceptance.");
            evidence.put("original_tables",originalRows.size());evidence.put("original_full_column_rows",30);evidence.put("changed_original_tables",changed);
            List<Map<String,Object>> runs=new ArrayList<>();for(Trace trace:traces)runs.add(trace.evidence());evidence.put("actual_statements_and_connections",runs);
            String value=System.getProperty("joint.source0403.evidence.dir");assertNotNull(value,"Explicit raw evidence output required");
            Path dir=Paths.get(value).toAbsolutePath().normalize();Files.createDirectories(dir);
            Path target=dir.resolve(test.getTestMethod().orElseThrow(IllegalStateException::new).getName()+"-"+UUID.randomUUID()+".json");
            Files.write(target,JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence),StandardOpenOption.CREATE_NEW);
            assertTrue(changed.isEmpty(),"Original multiset changed: "+changed);
        } finally {tenant.close();}
    }


    private static final String WS_RUNTIME_SQL="SELECT quote_json,status_json,writer_generation,control_revision,snapshot_version FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?";
    /** Actual handler and MySQL reader/writer; only session transport and tenant/catalog bindings are mocks. */
    @Test void actualWsThreeClientsShareTwoSymbolOnePhysicalTenantSnapshotAcrossLateWriter() throws Exception {
        Fixture a=new Fixture(),b=new Fixture();
        for(Fixture fixture:Arrays.asList(a,b)){fixture.source(2,"100.12345678",fixture.received);fixture.pump();assertTrue(fixture.project());}
        ForexQuoteMarketService market=consumerMarket(a);
        MarketWebSocketHandler ws=new MarketWebSocketHandler();ExecutorService reader=Executors.newSingleThreadExecutor();
        CountDownLatch captured=new CountDownLatch(1),resumeRead=new CountDownLatch(1);
        List<List<WsPriceObservation>> outputs=Arrays.asList(new CopyOnWriteArrayList<>(),new CopyOnWriteArrayList<>(),new CopyOnWriteArrayList<>());
        java.util.concurrent.atomic.AtomicReference<Trace> transportTrace=new java.util.concurrent.atomic.AtomicReference<>();
        List<List<String>> subsets=Arrays.asList(Collections.singletonList(a.config.getSymbol()),Collections.singletonList(b.config.getSymbol()),Arrays.asList(a.config.getSymbol(),b.config.getSymbol()));
        ThreadPoolExecutor sends=(ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(ws,"senders");
        try {
            b.config.setName(b.config.getSymbol());b.config.setBaseCurrency("TEST");b.config.setQuoteCurrency("USD");b.config.setMarketSource("yahoo");b.config.setSourceCategory("Metal");b.config.setCategory("Metal");
            com.gtcfesk.exchange.repository.TradingSymbolRepository catalog=(com.gtcfesk.exchange.repository.TradingSymbolRepository)org.springframework.test.util.ReflectionTestUtils.getField(market,"symbols");
            org.mockito.Mockito.when(catalog.findAllByTenantId(TENANT)).thenReturn(Arrays.asList(a.config,b.config));
            org.mockito.Mockito.when(catalog.findByTenantIdAndId(TENANT,b.id)).thenReturn(Optional.of(b.config));market.refreshSymbols();
            org.springframework.test.util.ReflectionTestUtils.setField(ws,"marketService",market);
            com.gtcfesk.exchange.control.Tenant binding=new com.gtcfesk.exchange.control.Tenant();binding.setId(TENANT);binding.setStatus("ACTIVE");binding.setDomainVerified(true);binding.setFrontendHost("source-consumer.invalid");
            com.gtcfesk.exchange.control.TenantRepository tenants=org.mockito.Mockito.mock(com.gtcfesk.exchange.control.TenantRepository.class);
            org.mockito.Mockito.when(tenants.findById(TENANT)).thenReturn(Optional.of(binding));org.springframework.test.util.ReflectionTestUtils.setField(ws,"tenants",tenants);
            for(int index=0;index<subsets.size();index++){
                final int clientIndex=index;final List<WsPriceObservation> output=outputs.get(index);
                org.springframework.web.socket.WebSocketSession session=org.mockito.Mockito.mock(org.springframework.web.socket.WebSocketSession.class);
                org.mockito.Mockito.when(session.getAttributes()).thenReturn(Map.of("tenantId",TENANT,"frontendHost","source-consumer.invalid"));
                org.mockito.Mockito.when(session.isOpen()).thenReturn(true);org.mockito.Mockito.when(session.getId()).thenReturn("source-ws-batch-"+index);
                org.mockito.Mockito.doAnswer(call->{
                    long at=System.nanoTime();boolean transactionActive=TransactionSynchronizationManager.isActualTransactionActive();
                    String payload=((org.springframework.web.socket.TextMessage)call.getArgument(0)).getPayload();Map<String,Object> frame=a.store.decode(payload);
                    if("price".equals(frame.get("type"))){
                        output.add(new WsPriceObservation(frame,at,transactionActive));Trace observed=transportTrace.get();
                        if(observed!=null){Map<String,Object> event=new LinkedHashMap<>();event.put("kind","ws-price-frame-send");event.put("at_nanos",at);event.put("transaction_active",transactionActive);event.put("session","source-ws-batch-"+clientIndex);event.put("tenant_id",TENANT);event.put("subset",new ArrayList<>(subsets.get(clientIndex)));event.put("frame",frame);event.put("payload",payload);event.put("consumer_connection_ids",new ArrayList<>(observed.connectionIds));event.put("consumer_commit_ack_at_nanos",observed.commitAt);observed.events.add(event);}
                    }
                    return null;
                }).when(session).sendMessage(org.mockito.ArgumentMatchers.any(org.springframework.web.socket.TextMessage.class));
                ws.afterConnectionEstablished(session);
                Map<String,Object> subscription=new LinkedHashMap<>();subscription.put("action","subscribe");subscription.put("symbols",subsets.get(index));subscription.put("fastSymbols",subsets.get(index));
                ws.handleTextMessage(session,new org.springframework.web.socket.TextMessage(JSON.writeValueAsString(subscription)));
            }
            ScheduledExecutorService timers=(ScheduledExecutorService)org.springframework.test.util.ReflectionTestUtils.getField(ws,"scheduler");
            timers.shutdown();assertTrue(timers.awaitTermination(10,TimeUnit.SECONDS));awaitWsIdle(ws,sends,null);for(List<WsPriceObservation> output:outputs)output.clear();
            Map<String,Object> oldRuntimeA=a.runtime(),oldRuntimeB=b.runtime();
            Map<String,Long> oldVersions=new LinkedHashMap<>();oldVersions.put(a.config.getSymbol(),number(oldRuntimeA,"snapshot_version"));oldVersions.put(b.config.getSymbol(),number(oldRuntimeB,"snapshot_version"));
            Map<String,Object> progressA=a.progress(),progressB=b.progress();List<Map<String,Object>> bodyA=a.minutes(),bodyB=b.minutes(),factsA=a.rawRows(),factsB=b.rawRows();
            Trace old=trace("actual-ws-three-client-two-symbol-one-physical-tenant-RR");source.clear();old.pureReads=true;old.wsReadCaptured=captured;old.wsReadResume=resumeRead;transportTrace.set(old);
            old.beforeCommitCheck=()->{for(List<WsPriceObservation> output:outputs)assertTrue(output.isEmpty(),"Price sent before actual consumer COMMIT");assertEquals(0,sends.getActiveCount());assertTrue(sends.getQueue().isEmpty());};
            Future<?> first=reader.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(TENANT)){source.start(old);try{ws.push();}finally{source.clear();}}});
            assertTrue(captured.await(10,TimeUnit.SECONDS),"First actual runtime SELECT did not establish the RR snapshot");
            assertTrue(Objects.equals(old.wsReadSymbol,a.id)||Objects.equals(old.wsReadSymbol,b.id));
            Trace writer=trace("actual-ws-late-two-symbol-atomic-writer");
            try {a.store.transaction(()->{pumpWsPrice(a,"105.12345678");pumpWsPrice(b,"205.12345678");return null;});}
            finally{source.clear();}
            assertEquals(1,writer.connections);assertEquals(1,writer.connectionIds.size());assertEquals(1,writer.events.stream().filter(event->"commit".equals(event.get("kind"))).count());assertTrue(writer.commitAt>0);
            Map<String,Long> nextVersions=new LinkedHashMap<>();nextVersions.put(a.config.getSymbol(),number(a.runtime(),"snapshot_version"));nextVersions.put(b.config.getSymbol(),number(b.runtime(),"snapshot_version"));
            for(String symbol:oldVersions.keySet())assertEquals(oldVersions.get(symbol)+1,nextVersions.get(symbol).longValue());
            Map<String,Object> runtimeA=a.runtime(),runtimeB=b.runtime();
            resumeRead.countDown();first.get(10,TimeUnit.SECONDS);assertWsQuoteBatch(old,a,b);
            List<Map<String,Object>> oldReads=wsRuntimeReads(old);assertTrue(((Number)oldReads.get(1).get("at_nanos")).longValue()>writer.commitAt,"Second symbol SELECT must start after the late writer COMMIT");
            long firstReturned=old.events.stream().filter(event->"execute-return".equals(event.get("kind"))&&WS_RUNTIME_SQL.equals(event.get("sql"))).mapToLong(event->((Number)event.get("at_nanos")).longValue()).min().orElseThrow(IllegalStateException::new);
            assertTrue(firstReturned<writer.commitStartedAt,"First symbol read must precede the late writer");
            awaitWsIdle(ws,sends,outputs);
            Map<String,String> oldPrices=new LinkedHashMap<>();oldPrices.put(a.config.getSymbol(),"100.12345678");oldPrices.put(b.config.getSymbol(),"100.12345678");
            assertWsFrames(outputs,subsets,oldPrices,old,a,b,oldRuntimeA,oldRuntimeB);
            for(List<WsPriceObservation> output:outputs)output.clear();
            Trace next=trace("next-ws-three-client-two-symbol-new-committed-tenant-batch");next.pureReads=true;transportTrace.set(next);
            next.beforeCommitCheck=()->{for(List<WsPriceObservation> output:outputs)assertTrue(output.isEmpty(),"Price sent before actual consumer COMMIT");assertEquals(0,sends.getActiveCount());assertTrue(sends.getQueue().isEmpty());};
            try{ws.push();}finally{source.clear();}
            assertWsQuoteBatch(next,a,b);awaitWsIdle(ws,sends,outputs);
            Map<String,String> nextPrices=new LinkedHashMap<>();nextPrices.put(a.config.getSymbol(),"105.12345678");nextPrices.put(b.config.getSymbol(),"205.12345678");
            assertWsFrames(outputs,subsets,nextPrices,next,a,b,runtimeA,runtimeB);
            assertEquals(runtimeA,a.runtime());assertEquals(runtimeB,b.runtime());assertEquals(progressA,a.progress());assertEquals(progressB,b.progress());
            assertEquals(bodyA,a.minutes());assertEquals(bodyB,b.minutes());assertEquals(factsA,a.rawRows());assertEquals(factsB,b.rawRows());
            for(Map<String,Object> status:market.sourceStatus())assertEquals(0,((Number)status.get("pendingKlines")).intValue());
        } finally {resumeRead.countDown();reader.shutdownNow();try{assertTrue(reader.awaitTermination(10,TimeUnit.SECONDS));}finally{ws.destroy();market.stop();}}
    }
    private static final class WsPriceObservation {
        final Map<String,Object> frame;final long at;final boolean transactionActive;
        WsPriceObservation(Map<String,Object> frame,long at,boolean transactionActive){this.frame=frame;this.at=at;this.transactionActive=transactionActive;}
    }
    private static void pumpWsPrice(Fixture fixture,String price){
        long now=fixture.store.runtime.clock();Map<String,Object> raw=new LinkedHashMap<>();raw.put("price",new BigDecimal(price));raw.put("timestamp",now);raw.put("sourceTimestamp",now);raw.put("available",true);raw.put("expiresAt",now+60000);
        fixture.controls.pump(fixture.config,raw,now,60000);
    }
    private static void awaitWsIdle(MarketWebSocketHandler ws,ThreadPoolExecutor sends,List<List<WsPriceObservation>> outputs) throws Exception {
        Map<?,?> clients=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(ws,"clients");
        for(int attempt=0;attempt<1000;attempt++){
            boolean busy=sends.getActiveCount()!=0||!sends.getQueue().isEmpty();
            for(Object client:clients.values())busy|=((java.util.concurrent.atomic.AtomicBoolean)org.springframework.test.util.ReflectionTestUtils.getField(client,"sending")).get();
            if(outputs!=null)for(List<WsPriceObservation> output:outputs)busy|=output.isEmpty();
            if(!busy)return;Thread.sleep(10);
        }
        fail("Actual WS send executor, client sending flags or complete frame outputs did not drain");
    }
    private static List<Map<String,Object>> wsRuntimeReads(Trace trace){
        List<Map<String,Object>> reads=new ArrayList<>();for(Map<String,Object> event:trace.events)if("execute".equals(event.get("kind"))&&WS_RUNTIME_SQL.equals(event.get("sql")))reads.add(event);return reads;
    }
    private static void assertWsQuoteBatch(Trace trace,Fixture a,Fixture b){
        assertPureConsumer(trace);assertEquals(0,trace.failures);assertEquals(1,trace.events.stream().filter(event->"commit".equals(event.get("kind"))).count());assertTrue(trace.commitAt>0);
        List<Map<String,Object>> reads=wsRuntimeReads(trace);assertEquals(2,reads.size());Map<Long,Integer> counts=new LinkedHashMap<>();
        for(Map<String,Object> read:reads){Map<?,?> arguments=(Map<?,?>)read.get("arguments");assertEquals(TENANT,((Number)arguments.get(1)).longValue());counts.merge(((Number)arguments.get(2)).longValue(),1,Integer::sum);}
        assertEquals(new HashSet<>(Arrays.asList(a.id,b.id)),counts.keySet());assertEquals(Integer.valueOf(1),counts.get(a.id));assertEquals(Integer.valueOf(1),counts.get(b.id));
    }
    private static void assertWsFrames(List<List<WsPriceObservation>> outputs,List<List<String>> subsets,Map<String,String> prices,Trace trace,Fixture a,Fixture b,Map<String,Object> runtimeA,Map<String,Object> runtimeB){
        long commitAt=trace.commitAt;assertTrue(commitAt>0);assertEquals(3,trace.events.stream().filter(event->"ws-price-frame-send".equals(event.get("kind"))).count());
        for(int index=0;index<outputs.size();index++){
            List<WsPriceObservation> output=outputs.get(index);assertEquals(1,output.size(),"Each client gets exactly one complete frame from this batch");WsPriceObservation observation=output.get(0);
            assertFalse(observation.transactionActive);assertTrue(observation.at>commitAt,"Transport must begin after the actual consumer COMMIT");
            Map<?,?> data=(Map<?,?>)observation.frame.get("data");assertEquals(new HashSet<>(subsets.get(index)),data.keySet());
            for(String symbol:subsets.get(index)){Map<?,?> quote=(Map<?,?>)data.get(symbol);Map<String,Object> runtime=symbol.equals(a.config.getSymbol())?runtimeA:runtimeB;assertEquals(0,new BigDecimal(prices.get(symbol)).compareTo(new BigDecimal(String.valueOf(quote.get("price")))));assertEquals(TENANT,((Number)quote.get("tenantId")).longValue());assertEquals(symbol.equals(a.config.getSymbol())?a.id:b.id,((Number)quote.get("symbolId")).longValue());assertEquals(number(runtime,"snapshot_version"),((Number)quote.get("quoteVersion")).longValue());assertEquals(number(runtime,"writer_generation"),((Number)quote.get("writerGeneration")).longValue());assertEquals(number(runtime,"control_revision"),((Number)quote.get("controlRevision")).longValue());assertEquals(Boolean.TRUE,quote.get("available"));}
        }
        Map<?,?> union=(Map<?,?>)outputs.get(2).get(0).frame.get("data");
        assertEquals(((Map<?,?>)outputs.get(0).get(0).frame.get("data")).get(a.config.getSymbol()),union.get(a.config.getSymbol()));
        assertEquals(((Map<?,?>)outputs.get(1).get(0).frame.get("data")).get(b.config.getSymbol()),union.get(b.config.getSymbol()));
    }

    @Test void actualHttpKlineAndWsPriceUseReadOnlyRRAndNeverPumpOrQueue() throws Exception {
        Fixture f=new Fixture();f.source(2,"100.12345678",f.received);f.pump();assertTrue(f.project());
        ForexQuoteMarketService market=consumerMarket(f);
        MarketKlineController controller=new MarketKlineController();org.springframework.test.util.ReflectionTestUtils.setField(controller,"marketService",market);
        org.springframework.test.web.servlet.MockMvc http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        Map<String,Object> runtimeBefore=f.runtime(),progressBefore=f.progress();List<Map<String,Object>> bodyBefore=f.minutes(),factsBefore=f.rawRows();
        try {
            Trace get=trace("actual-http-source-projection-readonly-RR");get.pureReads=true;
            String response;
            try { response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/market/kline/history/"+f.config.getSymbol())
                .param("interval","1m").param("limit","2").param("endTime",Long.toString(f.first+119999)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
            } finally {source.clear();}
            assertEquals(Arrays.asList(bar(f.first,"100.12345678"),bar(f.first+60000,"100.12345678")),
                ControlHistoryStore.rows(f.store.decode(response)));
            assertPureConsumer(get);
            MarketWebSocketHandler ws=new MarketWebSocketHandler();org.springframework.test.util.ReflectionTestUtils.setField(ws,"marketService",market);
            com.gtcfesk.exchange.control.Tenant binding=new com.gtcfesk.exchange.control.Tenant();binding.setId(TENANT);binding.setStatus("ACTIVE");binding.setDomainVerified(true);binding.setFrontendHost("source-consumer.invalid");
            com.gtcfesk.exchange.control.TenantRepository tenants=org.mockito.Mockito.mock(com.gtcfesk.exchange.control.TenantRepository.class);
            org.mockito.Mockito.when(tenants.findById(TENANT)).thenReturn(Optional.of(binding));org.springframework.test.util.ReflectionTestUtils.setField(ws,"tenants",tenants);
            org.springframework.web.socket.WebSocketSession session=org.mockito.Mockito.mock(org.springframework.web.socket.WebSocketSession.class);
            org.mockito.Mockito.when(session.getAttributes()).thenReturn(Map.of("tenantId",TENANT,"frontendHost","source-consumer.invalid"));org.mockito.Mockito.when(session.isOpen()).thenReturn(true);
            try {
                ws.afterConnectionEstablished(session);ws.handleTextMessage(session,new org.springframework.web.socket.TextMessage("{\"action\":\"subscribe\",\"symbols\":[\""+f.config.getSymbol()+"\"],\"fastSymbols\":[\""+f.config.getSymbol()+"\"]}"));
                // Wait for the subscription callback; keep the actual send executor alive for the traced frame.
                ScheduledExecutorService timers=(ScheduledExecutorService)org.springframework.test.util.ReflectionTestUtils.getField(ws,"scheduler");
                timers.shutdown();assertTrue(timers.awaitTermination(10,TimeUnit.SECONDS));
                ThreadPoolExecutor sends=(ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(ws,"senders");
                for(int i=0;i<500 && (sends.getActiveCount()!=0 || !sends.getQueue().isEmpty());i++)Thread.sleep(10);
                assertEquals(0,sends.getActiveCount());assertTrue(sends.getQueue().isEmpty());org.mockito.Mockito.clearInvocations(session);
                Trace frame=trace("actual-ws-price-frame-readonly-RR");frame.pureReads=true;
                try {ws.push();} finally {source.clear();}
                assertPureConsumer(frame);
                org.mockito.ArgumentCaptor<org.springframework.web.socket.TextMessage> sent=org.mockito.ArgumentCaptor.forClass(org.springframework.web.socket.TextMessage.class);
                org.mockito.Mockito.verify(session,org.mockito.Mockito.timeout(5000).atLeastOnce()).sendMessage(sent.capture());
                assertTrue(sent.getAllValues().stream().anyMatch(message->"price".equals(f.store.decode(message.getPayload()).get("type"))));
            } finally {ws.destroy();}
            assertEquals(runtimeBefore,f.runtime());assertEquals(progressBefore,f.progress());assertEquals(bodyBefore,f.minutes());assertEquals(factsBefore,f.rawRows());
            for(Map<String,Object> status:market.sourceStatus())assertEquals(0,((Number)status.get("pendingKlines")).intValue());
        } finally {market.stop();}
    }
    @Test void actualHttpSourceReadPinsOldCommittedBoundaryAcrossLateWriterThenNextRequestSeesNew() throws Exception {
        Fixture f=new Fixture();f.source(2,"100.12345678",f.received);f.pump();assertTrue(f.project());
        ForexQuoteMarketService market=consumerMarket(f);MarketKlineController controller=new MarketKlineController();org.springframework.test.util.ReflectionTestUtils.setField(controller,"marketService",market);
        org.springframework.test.web.servlet.MockMvc http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        ExecutorService reader=Executors.newSingleThreadExecutor();CountDownLatch captured=new CountDownLatch(1),resumeRead=new CountDownLatch(1);
        Trace old=trace("actual-http-late-source-race-one-physical-RR");source.clear();old.pureReads=true;old.sourceReadCaptured=captured;old.sourceReadResume=resumeRead;
        try {
            Future<String> request=reader.submit(()->{try(TenantContext.Scope scope=TenantContext.open(TENANT)){
                source.start(old);
                try{return http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/market/kline/history/"+f.config.getSymbol())
                    .param("interval","1m").param("limit","2").param("endTime",Long.toString(f.first+119999)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();}
                finally{source.clear();}
            }});
            assertTrue(captured.await(10,TimeUnit.SECONDS),"Actual progress SELECT did not establish the RR snapshot");
            f.store.transaction(()->{f.store.sourceCandles(f.id,"1m",Collections.singletonList(bar(f.first,"105.12345678")),f.received+1);assertTrue(f.project());return null;});
            assertEquals(2,number(f.progress(),"input_revision"));resumeRead.countDown();
            assertEquals(Arrays.asList(bar(f.first,"100.12345678"),bar(f.first+60000,"100.12345678")),ControlHistoryStore.rows(f.store.decode(request.get(10,TimeUnit.SECONDS))));
            assertPureConsumer(old);
            Trace next=trace("next-http-observes-late-source-committed-boundary");next.pureReads=true;
            String response;
            try {response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/market/kline/history/"+f.config.getSymbol())
                .param("interval","1m").param("limit","2").param("endTime",Long.toString(f.first+119999))).andReturn().getResponse().getContentAsString();}
            finally{source.clear();}
            assertEquals(Arrays.asList(bar(f.first,"105.12345678"),bar(f.first+60000,"100.12345678")),ControlHistoryStore.rows(f.store.decode(response)));assertPureConsumer(next);
            assertThrows(IllegalStateException.class,()->f.store.transaction(()->f.derived.readSourceWindow(f.id,f.first,f.first)));
        } finally {resumeRead.countDown();reader.shutdownNow();assertTrue(reader.awaitTermination(10,TimeUnit.SECONDS));market.stop();}
    }
    private static void assertPureConsumer(Trace trace) {
        assertEquals(1,trace.connections);assertEquals(1,trace.connectionIds.size());assertFalse(trace.sql().isEmpty());assertTrue(trace.verifiedReadOnlyRRStatements>0);
        for(String sql:trace.sql()){assertTrue(sql.trim().toUpperCase(Locale.ROOT).startsWith("SELECT "),sql);assertFalse(sql.toUpperCase(Locale.ROOT).contains("FOR UPDATE"),sql);assertFalse(sql.toUpperCase(Locale.ROOT).contains("LOCK IN SHARE MODE"),sql);}
    }
    private ForexQuoteMarketService consumerMarket(Fixture f) {
        f.config.setName(f.config.getSymbol());f.config.setBaseCurrency("TEST");f.config.setQuoteCurrency("USD");f.config.setMarketSource("yahoo");f.config.setSourceCategory("Metal");f.config.setCategory("Metal");
        ForexQuoteMarketService market=new ForexQuoteMarketService();com.gtcfesk.exchange.repository.TradingSymbolRepository repository=org.mockito.Mockito.mock(com.gtcfesk.exchange.repository.TradingSymbolRepository.class);
        org.mockito.Mockito.when(repository.findAllByTenantId(TENANT)).thenReturn(Collections.singletonList(f.config));
        org.mockito.Mockito.when(repository.findByTenantIdAndId(TENANT,f.id)).thenReturn(Optional.of(f.config));
        org.springframework.test.util.ReflectionTestUtils.setField(market,"symbols",repository);org.springframework.test.util.ReflectionTestUtils.setField(market,"controls",f.controls);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"controlHistory",f.store);org.springframework.test.util.ReflectionTestUtils.setField(market,"klineMerger",new ControlledKlineMerger(f.store));
        org.springframework.test.util.ReflectionTestUtils.setField(market,"sourceHistory",f.adapter);org.springframework.test.util.ReflectionTestUtils.setField(market,"sourceProjectionEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"redis",org.mockito.Mockito.mock(RedisMarketService.class));market.refreshSymbols();return market;
    }

    /** Real loopback provider and actual background provider lane; TCP bridge invokes the actual controller.
     *  The bridge supplies tenant identity only. It is not application JWT/filter/Tomcat acceptance. */
    @Test void actualAutonomousLoopbackFeedMakesColdSourceCompletedPartialAndDirtyWithoutReaderWrites() throws Exception {
        Fixture first=new Fixture(),alias=new Fixture();
        long from=MinuteHistoryProjection.minute(first.store.runtime.clock())-120000;
        try(LocalSourceProvider provider=new LocalSourceProvider(from,2);
            AutonomousMarket service=new AutonomousMarket(first,Arrays.asList(first,alias),provider,true);
            SourceConsumerTcp reader=new SourceConsumerTcp(service.market)) {
            assertEquals(0,provider.klineCalls.get());
            Map<String,Object> cold=reader.read(first.config.getSymbol(),from+119999,2);
            assertTrue(ControlHistoryStore.rows(cold).isEmpty());reader.assertPureReads();
            assertEquals(0,provider.klineCalls.get(),"GET cannot create work before the writer starts");
            service.startActualLane();
            untilSource(()->first.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=? AND symbol_id=?",Integer.class,TENANT,first.id)==2,10000);
            assertTrue(provider.klineCalls.get()>0);assertTrue(provider.requests.stream().anyMatch(path->path.contains("/api/v3/klines?")&&path.contains("interval=1m")&&path.contains("limit=500")));
            assertEquals(2,alias.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=? AND symbol_id=?",Integer.class,TENANT,alias.id));
            assertEquals(1,number(first.runtime(),"source_input_revision"));assertEquals(1,number(alias.runtime(),"source_input_revision"));
            Map<String,Object> beforeProjection=reader.read(first.config.getSymbol(),from+119999,2);
            assertEquals("SOURCE_1M",((Map<?,?>)beforeProjection.get("data")).get("projectionKind"));
            assertTrue(ControlHistoryStore.rows(beforeProjection).isEmpty());assertEquals("pending",beforeProjection.get("status"));
            assertTrue(first.project());Map<String,Object> aliasRuntime=alias.runtime();
            assertTrue(first.adapter.project(alias.id,(String)aliasRuntime.get("owner_id"),number(aliasRuntime,"writer_generation"),number(aliasRuntime,"control_revision")));
            List<Map<String,Object>> expected=provider.expected("100.12345678");
            Map<String,Object> complete=reader.read(first.config.getSymbol(),from+119999,2);
            assertEquals("available",complete.get("status"));assertEquals(expected,ControlHistoryStore.rows(complete));
            Map<String,Object> partial=reader.read(first.config.getSymbol(),from+179999,3);
            assertEquals("partial",partial.get("status"));assertEquals(expected,ControlHistoryStore.rows(partial));
            long generation=number(first.runtime(),"writer_generation"),version=number(first.progress(),"fact_version");
            provider.firstPrice="105.12345678";
            // Actual 15-second freshness expiry; do not forge fetchedAt, runtime lease, cursor or source facts.
            untilSource(()->number(first.runtime(),"source_input_revision")>=2,22000);
            assertNotNull(first.runtime().get("source_dirty_from"));assertEquals(version,number(first.progress(),"fact_version"));
            Map<String,Object> dirty=reader.read(first.config.getSymbol(),from+119999,2);
            assertEquals("partial",dirty.get("status"));assertTrue(ControlHistoryStore.rows(dirty).isEmpty(),"An intersecting dirty publication is withheld");
            assertTrue(first.project());
            Map<String,Object> repaired=reader.read(first.config.getSymbol(),from+119999,2);
            assertEquals("available",repaired.get("status"));assertEquals(provider.expected("105.12345678"),ControlHistoryStore.rows(repaired));
            assertEquals(generation,number(first.runtime(),"writer_generation"));assertEquals(version+1,number(first.progress(),"fact_version"));
            reader.assertPureReads();service.assertBoundedAndRealSourceWrites();
        }
    }
    @Test void defaultDisabledSourceFeedPreservesBoundedLegacyFetchOnlyAfterConsumerCommit() throws Exception {
        Fixture f=new Fixture();long from=MinuteHistoryProjection.minute(f.store.runtime.clock())-86400000,end=from+119999;
        try(LegacyLocalSourceProvider provider=new LegacyLocalSourceProvider();
            AutonomousMarket service=new AutonomousMarket(f,Collections.singletonList(f),provider.base(),false);
            SourceConsumerTcp reader=new SourceConsumerTcp(service.market)) {
            java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
            reader.beforeCommitCheck=()->{checks.incrementAndGet();assertEquals(0,service.pendingKey("1m",2,end));assertEquals(0,provider.klineCalls.get());};
            Map<String,Object> cold=reader.read(f.config.getSymbol(),end,2);reader.beforeCommitCheck=null;
            assertTrue(ControlHistoryStore.rows(cold).isEmpty());assertTrue(Boolean.TRUE.equals(((Map<?,?>)cold.get("data")).get("pending")));
            assertEquals(1,checks.get(),"Actual read-only JDBC COMMIT boundary was observed");
            assertEquals(1,service.pendingKey("1m",2,end),"Only after the actual consumer commit may legacy work enter the existing queue");
            assertEquals(0,provider.klineCalls.get(),"The real lane is still stopped; the request must not do network I/O");
            reader.assertPureReads();service.assertQueueBounds();service.startActualLane();
            untilSource(()->f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period='1m' AND candle_at>=? AND candle_at<=?",Integer.class,TENANT,f.id,from,end)==2,10000);
            Map<String,Object> complete=reader.read(f.config.getSymbol(),end,2);
            assertEquals("available",((Map<?,?>)complete.get("data")).get("status"));assertEquals(provider.expected("1m",2,end),ControlHistoryStore.rows(complete));
            Thread.sleep(1200);assertEquals(1,provider.klineCalls.get(),"Default false disables autonomous candle feed, not committed legacy fetch");
            assertTrue(provider.requests.stream().filter(path->path.contains("/klines?")).allMatch(path->path.contains("interval=1m")&&path.contains("limit=2")&&path.contains("endTime="+end)));
            assertEquals(0,service.pendingKey("1m",2,end));reader.assertPureReads();service.assertBoundedAndRealSourceWrites();
        }
    }
    @Test void enabledSourceFeedPreservesNonMinuteLatestAndFixedOldLegacyWindowAfterCommit() throws Exception {
        Fixture f=new Fixture();long width=300000,from=Math.floorDiv(f.store.runtime.clock()-3*86400000L,width)*width,end=from+2*width-1;
        try(LegacyLocalSourceProvider provider=new LegacyLocalSourceProvider();
            AutonomousMarket service=new AutonomousMarket(f,Collections.singletonList(f),provider.base(),true);
            SourceConsumerTcp reader=new SourceConsumerTcp(service.market)) {
            java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
            reader.beforeCommitCheck=()->{checks.incrementAndGet();assertEquals(0,service.pendingKey("5m",2,null));assertEquals(0,provider.klineCalls.get());};
            Map<String,Object> cold=reader.readLatest(f.config.getSymbol(),"5m",2);reader.beforeCommitCheck=null;
            assertTrue(ControlHistoryStore.rows(cold).isEmpty());assertEquals(1,checks.get());assertEquals(1,service.pendingKey("5m",2,null));
            assertEquals(0,provider.klineCalls.get());reader.assertPureReads();service.startActualLane();
            untilSource(()->provider.hasRequest("5m",2,null)&&f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period='5m'",Integer.class,TENANT,f.id)==2,10000);
            Map<String,Object> latest=reader.readLatest(f.config.getSymbol(),"5m",2);
            assertEquals(provider.expected("5m",2,null),ControlHistoryStore.rows(latest));assertEquals("available",((Map<?,?>)latest.get("data")).get("status"));
            reader.beforeCommitCheck=()->{checks.incrementAndGet();assertEquals(0,service.pendingKey("5m",2,end));assertFalse(provider.hasRequest("5m",2,end));};
            Map<String,Object> oldCold=reader.read(f.config.getSymbol(),"5m",end,2);reader.beforeCommitCheck=null;
            assertTrue(ControlHistoryStore.rows(oldCold).isEmpty());assertTrue(Boolean.TRUE.equals(((Map<?,?>)oldCold.get("data")).get("pending")));assertEquals(2,checks.get());
            untilSource(()->provider.hasRequest("5m",2,end)&&f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period='5m' AND candle_at>=? AND candle_at<=?",Integer.class,TENANT,f.id,from,end)==2,10000);
            Map<String,Object> historical=reader.read(f.config.getSymbol(),"5m",end,2);
            assertEquals(provider.expected("5m",2,end),ControlHistoryStore.rows(historical));assertEquals("available",((Map<?,?>)historical.get("data")).get("status"));
            assertTrue(provider.requests.stream().filter(path->path.contains("/klines?")&&path.contains("interval=5m")).allMatch(path->path.contains("limit=2")));
            reader.assertPureReads();service.assertQueueBounds();assertTrue(service.writer.sql().stream().anyMatch(sql->sql.startsWith("INSERT INTO market_source_candle")));
        }
    }
    @SuppressWarnings("unchecked")
    @Test void actualLegacyConsumerRollbackDoesNotCreateProviderWork() throws Exception {
        Fixture f=new Fixture();long from=MinuteHistoryProjection.minute(f.store.runtime.clock())-86400000,end=from+119999;
        try(LegacyLocalSourceProvider provider=new LegacyLocalSourceProvider();
            AutonomousMarket service=new AutonomousMarket(f,Collections.singletonList(f),provider.base(),false)) {
            MarketKlineController controller=new MarketKlineController();org.springframework.test.util.ReflectionTestUtils.setField(controller,"marketService",service.market);
            Trace read=trace("actual-controller-legacy-readonly-RR-rollback-no-queue");read.pureReads=true;
            try {
                IllegalStateException failure=assertThrows(IllegalStateException.class,()->service.market.readSnapshot(()->{
                    org.springframework.http.ResponseEntity<?> response=controller.history(f.config.getSymbol(),"1m",end,2);
                    assertEquals(200,response.getStatusCodeValue());Map<String,Object> body=(Map<String,Object>)response.getBody();assertNotNull(body);assertTrue(ControlHistoryStore.rows(body).isEmpty());
                    assertTrue(Boolean.TRUE.equals(((Map<?,?>)body.get("data")).get("pending")));assertEquals(0,service.pendingKey("1m",2,end));
                    throw new IllegalStateException("intentional legacy consumer rollback");
                }));
                assertEquals("intentional legacy consumer rollback",failure.getMessage());
            }finally{source.clear();}
            assertPureConsumer(read);assertEquals(0,read.commitAt);assertTrue(read.events.stream().anyMatch(event->"rollback".equals(event.get("kind"))));
            assertEquals(0,service.pendingKey("1m",2,end));assertEquals(0,provider.klineCalls.get());service.startActualLane();Thread.sleep(1200);
            assertEquals(0,provider.klineCalls.get());assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=? AND symbol_id=?",Integer.class,TENANT,f.id));service.assertQueueBounds();
        }
    }
    @Test void actualAutonomousProviderOver500IsRejectedBeforeSourceBodyOrDirtyReceiptWrites() throws Exception {
        Fixture f=new Fixture();long from=MinuteHistoryProjection.minute(f.store.runtime.clock())-120000;
        try(LocalSourceProvider provider=new LocalSourceProvider(from,501);
            AutonomousMarket service=new AutonomousMarket(f,Collections.singletonList(f),provider,true);
            SourceConsumerTcp reader=new SourceConsumerTcp(service.market)) {
            service.startActualLane();
            untilSource(()->service.market.sourceStatus().stream().anyMatch(row->"Crypto".equals(row.get("category"))&&((Number)row.get("klineFailures")).intValue()>0),10000);
            assertTrue(provider.klineCalls.get()>0);assertTrue(provider.requests.stream().filter(path->path.contains("/klines?")).allMatch(path->path.contains("limit=500")));
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=? AND symbol_id=?",Integer.class,TENANT,f.id));
            assertEquals(0,number(f.runtime(),"source_input_revision"));assertNull(f.runtime().get("source_dirty_from"));
            assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM s4_history_projection_progress WHERE tenant_id=? AND symbol_id=?",Integer.class,TENANT,f.id));
            assertTrue(ControlHistoryStore.rows(reader.read(f.config.getSymbol(),from+119999,2)).isEmpty());
            reader.assertPureReads();service.assertQueueBounds();
            assertTrue(service.writer.sql().stream().noneMatch(sql->sql.startsWith("INSERT INTO market_source_candle")||sql.startsWith("UPDATE market_engine_runtime SET source_input_revision=")));
        }
    }
    private static void untilSource(java.util.function.BooleanSupplier condition,long timeout) throws Exception {
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(25);
        assertTrue(condition.getAsBoolean(),"Actual bounded SOURCE worker did not reach expected state within "+timeout+"ms");
    }
    private final class AutonomousMarket implements AutoCloseable {
        final ForexQuoteMarketService market=new ForexQuoteMarketService();final MarketHttp transport=new MarketHttp();
        final Trace writer=new Trace("actual-autonomous-local-provider-SOURCE-writer");
        AutonomousMarket(Fixture authority,List<Fixture> aliases,LocalSourceProvider provider,boolean enabled) {
            this(authority,aliases,provider.base(),enabled);
        }
        AutonomousMarket(Fixture authority,List<Fixture> aliases,String providerBase,boolean enabled) {
            assertFalse((Boolean)org.springframework.test.util.ReflectionTestUtils.getField(market,"sourceProjectionEnabled"),"New feature is default-off");
            org.springframework.test.util.ReflectionTestUtils.setField(market,"sourceProjectionEnabled",enabled);
            com.gtcfesk.exchange.repository.TradingSymbolRepository repository=org.mockito.Mockito.mock(com.gtcfesk.exchange.repository.TradingSymbolRepository.class);
            List<TradingSymbol> configs=new ArrayList<>();
            for(Fixture fixture:aliases){
                TradingSymbol config=fixture.config;config.setAlltickSymbol("BTCUSDT");config.setName(config.getSymbol());config.setBaseCurrency("BTC");config.setQuoteCurrency("USD");config.setMarketSource("binance");config.setSourceCategory("Crypto");config.setCategory("Crypto");
                fixture.db.update("UPDATE trading_symbol SET alltick_symbol='BTCUSDT',base_currency='BTC',quote_currency='USD',market_source='binance',source_category='Crypto',category='Crypto' WHERE tenant_id=? AND id=?",TENANT,fixture.id);
                configs.add(config);org.mockito.Mockito.when(repository.findByTenantIdAndId(TENANT,fixture.id)).thenReturn(Optional.of(config));
            }
            org.mockito.Mockito.when(repository.findAllByTenantId(TENANT)).thenReturn(configs);
            ExchangeQuoteSource exchange=new ExchangeQuoteSource();exchange.http=transport;exchange.spotUrl=exchange.futuresUrl=exchange.okxUrl=providerBase;
            MarketQuoteSource actualProvider=new MarketQuoteSource();org.springframework.test.util.ReflectionTestUtils.setField(actualProvider,"exchange",exchange);org.springframework.test.util.ReflectionTestUtils.setField(actualProvider,"http",transport);
            org.springframework.test.util.ReflectionTestUtils.setField(actualProvider,"yahooUrl",providerBase);org.springframework.test.util.ReflectionTestUtils.setField(actualProvider,"alltickUrl",providerBase);
            org.springframework.test.util.ReflectionTestUtils.setField(actualProvider,"systemConfigService",org.mockito.Mockito.mock(com.gtcfesk.exchange.admin.SystemConfigService.class));
            org.springframework.test.util.ReflectionTestUtils.setField(market,"source",actualProvider);org.springframework.test.util.ReflectionTestUtils.setField(market,"exchangeSource",exchange);org.springframework.test.util.ReflectionTestUtils.setField(market,"http",transport);
            org.springframework.test.util.ReflectionTestUtils.setField(market,"symbols",repository);org.springframework.test.util.ReflectionTestUtils.setField(market,"controls",authority.controls);org.springframework.test.util.ReflectionTestUtils.setField(market,"controlHistory",authority.store);
            org.springframework.test.util.ReflectionTestUtils.setField(market,"klineMerger",new ControlledKlineMerger(authority.store));org.springframework.test.util.ReflectionTestUtils.setField(market,"sourceHistory",authority.adapter);
            org.springframework.test.util.ReflectionTestUtils.setField(market,"redis",org.mockito.Mockito.mock(RedisMarketService.class));
            org.springframework.test.util.ReflectionTestUtils.setField(market,"tenantJobs",new TenantJobRunner(authority.db,authority.manager));
            traces.add(writer);source.backgroundSourceWriter=writer;
            market.refreshSymbols(); // Register genuine configured code, but leave the real lane stopped for cold TCP proof.
        }
        void startActualLane(){org.springframework.test.util.ReflectionTestUtils.setField(market,"running",true);market.refreshSymbols();}
        int pendingKey(String interval,int limit,Long end){Map<?,?> states=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(market,"tenantStates");Object state=states.get(TENANT);Map<?,?> groups=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(state,"groups");Object group=groups.get("Crypto");synchronized(group){Map<?,?> pending=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(group,"pending");return pending.containsKey("BTCUSDT:"+interval+":"+limit+(end==null?"":":"+end))?1:0;}}
        void assertQueueBounds(){for(Map<String,Object> group:market.sourceStatus()){assertTrue(((Number)group.get("threads")).intValue()<=1);assertTrue(((Number)group.get("schedulerQueue")).intValue()<=1);assertTrue(((Number)group.get("pendingKlines")).intValue()<=32);}}
        void assertBoundedAndRealSourceWrites(){assertQueueBounds();assertTrue(writer.connections>0);assertTrue(writer.sql().stream().anyMatch(sql->sql.startsWith("INSERT INTO market_source_candle")));assertTrue(writer.sql().stream().anyMatch(sql->sql.startsWith("UPDATE market_engine_runtime SET source_input_revision=")));}
        @Override public void close() throws Exception {market.stop();Map<?,?> states=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(market,"tenantStates");for(Object state:states.values()){Map<?,?> groups=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(state,"groups");for(Object group:groups.values()){ScheduledThreadPoolExecutor lane=(ScheduledThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(group,"executor");assertTrue(lane.awaitTermination(10,TimeUnit.SECONDS));}}source.backgroundSourceWriter=null;transport.stop();}
    }
    private static final class LocalSourceProvider implements AutoCloseable {
        final long from;final int count;final long quoteAt=System.currentTimeMillis();volatile String firstPrice="100.12345678";
        final java.util.concurrent.atomic.AtomicInteger klineCalls=new java.util.concurrent.atomic.AtomicInteger(),tickerCalls=new java.util.concurrent.atomic.AtomicInteger();
        final List<String> requests=new CopyOnWriteArrayList<>();final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        final ExecutorService workers=Executors.newFixedThreadPool(2);final com.sun.net.httpserver.HttpServer server;
        LocalSourceProvider(long from,int count) throws Exception {this.from=from;this.count=count;server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),8);server.setExecutor(workers);server.createContext("/",this::reply);server.start();}
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void reply(com.sun.net.httpserver.HttpExchange request){try{String path=request.getRequestURI().toString();requests.add(path);String body;
            if(path.contains("/klines?")){klineCalls.incrementAndGet();assertTrue(path.contains("symbol=BTCUSDT"));assertTrue(path.contains("interval=1m"));assertTrue(path.contains("limit=500"));List<List<Object>> rows=new ArrayList<>();String captured=firstPrice;for(int i=0;i<count;i++){String price=i==0?captured:"200.87654321";rows.add(Arrays.asList(from+i*60000L,price,price,price,price,"1",from+i*60000L+59999,"100"));}body=JSON.writeValueAsString(rows);}
            else if(path.contains("/ticker/24hr?")){tickerCalls.incrementAndGet();body="{\"symbol\":\"BTCUSDT\",\"lastPrice\":\"100.12345678\",\"closeTime\":"+quoteAt+"}";}
            else body="{\"chart\":{\"result\":[]},\"spark\":{\"result\":[]}}";
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);
        }catch(Throwable error){failure.compareAndSet(null,error);}finally{request.close();}}
        List<Map<String,Object>> expected(String price){List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<2;i++){Map<String,Object> row=new LinkedHashMap<>();row.put("timestamp",from+i*60000L);for(String field:Arrays.asList("open_price","high_price","low_price","close_price"))row.put(field,new BigDecimal(i==0?price:"200.87654321"));row.put("volume",new BigDecimal("1.0"));row.put("turnover",new BigDecimal("100.0"));rows.add(row);}return rows;}
        @Override public void close() throws Exception {server.stop(0);workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));assertNull(failure.get(),"Actual local provider fixture failed: "+failure.get());}
    }
    /** Independent legacy fixture: the autonomous 500/501 fixture above remains byte-for-byte strict. */
    private static final class LegacyLocalSourceProvider implements AutoCloseable {
        final long quoteAt=System.currentTimeMillis();
        final java.util.concurrent.atomic.AtomicInteger klineCalls=new java.util.concurrent.atomic.AtomicInteger();
        final List<String> requests=new CopyOnWriteArrayList<>();final List<Map<String,String>> candleRequests=new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        final ExecutorService workers=Executors.newFixedThreadPool(2);final com.sun.net.httpserver.HttpServer server;
        LegacyLocalSourceProvider() throws Exception {server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),8);server.setExecutor(workers);server.createContext("/",this::reply);server.start();}
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void reply(com.sun.net.httpserver.HttpExchange request){try{String path=request.getRequestURI().toString();requests.add(path);String body;
            if(path.contains("/klines?")){Map<String,String> fields=new HashMap<>();for(String field:request.getRequestURI().getRawQuery().split("&")){String[] pair=field.split("=",2);assertEquals(2,pair.length);fields.put(pair[0],java.net.URLDecoder.decode(pair[1],"UTF-8"));}
                assertEquals("BTCUSDT",fields.get("symbol"));String interval=fields.get("interval");int count=Integer.parseInt(fields.get("limit"));Long end=fields.containsKey("endTime")?Long.valueOf(fields.get("endTime")):null;
                assertTrue("1m".equals(interval)&&count==500&&end==null || count==2&&Arrays.asList("1m","5m").contains(interval));
                candleRequests.add(Collections.unmodifiableMap(new HashMap<>(fields)));klineCalls.incrementAndGet();List<List<Object>> rows=new ArrayList<>();long width="5m".equals(interval)?300000:60000,first=Math.floorDiv(end==null?quoteAt:end,width)*width-(count-1L)*width;
                for(int i=0;i<count;i++){String price=i==0?"111.12345678":"222.87654321";rows.add(Arrays.asList(first+i*width,price,price,price,price,"1",first+(i+1)*width-1,"100"));}body=JSON.writeValueAsString(rows);
            }else if(path.contains("/ticker/24hr?"))body="{\"symbol\":\"BTCUSDT\",\"lastPrice\":\"100.12345678\",\"closeTime\":"+quoteAt+"}";
            else body="{\"chart\":{\"result\":[]},\"spark\":{\"result\":[]}}";
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);
        }catch(Throwable error){failure.compareAndSet(null,error);}finally{request.close();}}
        boolean hasRequest(String interval,int count,Long end){return candleRequests.stream().anyMatch(fields->interval.equals(fields.get("interval"))&&Integer.toString(count).equals(fields.get("limit"))&&Objects.equals(end==null?null:Long.toString(end),fields.get("endTime")));}
        List<Map<String,Object>> expected(String interval,int count,Long end){long width="5m".equals(interval)?300000:60000,first=Math.floorDiv(end==null?quoteAt:end,width)*width-(count-1L)*width;List<Map<String,Object>> result=new ArrayList<>();for(int i=0;i<count;i++){Map<String,Object> row=new LinkedHashMap<>();long seconds=(first+i*width)/1000;if(seconds>=Integer.MIN_VALUE&&seconds<=Integer.MAX_VALUE)row.put("timestamp",(int)seconds);else row.put("timestamp",seconds);for(String field:Arrays.asList("open_price","high_price","low_price","close_price"))row.put(field,new BigDecimal(i==0?"111.12345678":"222.87654321"));row.put("volume",new BigDecimal("1.0"));row.put("turnover",new BigDecimal("100.0"));result.add(row);}return result;}
        @Override public void close() throws Exception {server.stop(0);workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));assertNull(failure.get(),"Actual independent legacy provider fixture failed: "+failure.get());}
    }
    /** A real TCP transport bridge to the actual controller, not an auth or servlet-stack replacement. */
    private final class SourceConsumerTcp implements AutoCloseable {
        final MarketKlineController controller=new MarketKlineController();final com.sun.net.httpserver.HttpServer server;
        final ExecutorService workers=Executors.newFixedThreadPool(2);final List<Trace> reads=new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        final org.springframework.web.client.RestTemplate client;
        volatile Runnable beforeCommitCheck;
        SourceConsumerTcp(ForexQuoteMarketService market) throws Exception {org.springframework.test.util.ReflectionTestUtils.setField(controller,"marketService",market);server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),8);server.setExecutor(workers);server.createContext("/api/market/kline/",this::reply);server.start();org.springframework.http.client.SimpleClientHttpRequestFactory settings=new org.springframework.http.client.SimpleClientHttpRequestFactory();settings.setConnectTimeout(1000);settings.setReadTimeout(5000);client=new org.springframework.web.client.RestTemplate(settings);}
        Map<String,Object> read(String symbol,long end,int count){return read(symbol,"1m",end,count);}
        Map<String,Object> read(String symbol,String interval,long end,int count){return readUrl("/api/market/kline/history/"+symbol+"?interval="+interval+"&limit="+count+"&endTime="+end);}
        Map<String,Object> readLatest(String symbol,String interval,int count){return readUrl("/api/market/kline/"+symbol+"?interval="+interval+"&limit="+count);}
        Map<String,Object> readUrl(String path){String response=client.getForObject("http://127.0.0.1:"+server.getAddress().getPort()+path,String.class);assertNotNull(response);try{return JSON.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(response,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}catch(Exception error){throw new IllegalStateException(error);}}
        void reply(com.sun.net.httpserver.HttpExchange request){Trace trace=new Trace("actual-TCP-bridge-controller-readonly-RR");trace.pureReads=true;trace.beforeCommitCheck=beforeCommitCheck;reads.add(trace);traces.add(trace);
            try(TenantContext.Scope scope=TenantContext.open(TENANT)){source.start(trace);String path=request.getRequestURI().getPath();boolean history=path.startsWith("/api/market/kline/history/");String symbol=path.substring((history?"/api/market/kline/history/":"/api/market/kline/").length());Map<String,String> query=new HashMap<>();for(String field:request.getRequestURI().getRawQuery().split("&")){String[] pair=field.split("=",2);assertEquals(2,pair.length);query.put(pair[0],java.net.URLDecoder.decode(pair[1],"UTF-8"));}
                org.springframework.http.ResponseEntity<?> response=history?controller.history(symbol,query.get("interval"),Long.parseLong(query.get("endTime")),Integer.parseInt(query.get("limit"))):controller.getKline(symbol,query.get("interval"),Integer.valueOf(query.get("limit")),null);assertEquals(200,response.getStatusCodeValue());byte[] bytes=JSON.writeValueAsBytes(response.getBody());request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);
            }catch(Throwable error){failure.compareAndSet(null,error);}finally{source.clear();request.close();}}
        void assertPureReads(){assertFalse(reads.isEmpty());assertNull(failure.get(),"Actual TCP bridge failed: "+failure.get());for(Trace trace:reads)assertPureConsumer(trace);}
        @Override public void close() throws Exception {server.stop(0);workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));assertPureReads();}
    }

    @Test void actualSourceThousandMinuteFirstDirtyPageNeverReturnsLaterCleanSuffix() throws Exception {
        Fixture f=new Fixture();f.source(1000,"100.12345678",f.received);f.pump();assertTrue(f.project());assertTrue(f.project());
        ForexQuoteMarketService market=consumerMarket(f);long end=f.first+1000*60000L-1;
        try {
            Map<String,Object> complete=market.readSnapshot(()->market.historicalKline(f.config.getSymbol(),"1m",1000,end));
            assertEquals("available",complete.get("status"));assertEquals(sourceBars(f,0,1000,"100.12345678"),ControlHistoryStore.rows(complete));
            f.store.sourceCandles(f.id,"1m",Collections.singletonList(bar(f.first+50*60000L,"105.12345678")),f.received+1);
            Map<String,Object> runtime=f.runtime(),progress=f.progress();List<Map<String,Object>> minutes=f.minutes(),raw=f.rawRows();
            Trace read=trace("actual-source-1000-first500-dirty-clean-suffix-withheld");read.pureReads=true;Map<String,Object> response;
            try {response=market.readSnapshot(()->market.historicalKline(f.config.getSymbol(),"1m",1000,end));}finally{source.clear();}
            assertEquals("partial",response.get("status"));assertTrue(Boolean.TRUE.equals(((Map<?,?>)response.get("data")).get("pending")));assertTrue(ControlHistoryStore.rows(response).isEmpty());
            assertEquals(0,((Number)((Map<?,?>)response.get("data")).get("historyReceivedCutoffMinimum")).longValue());assertPureConsumer(read);
            assertEquals(runtime,f.runtime());assertEquals(progress,f.progress());assertEquals(minutes,f.minutes());assertEquals(raw,f.rawRows());
        }finally{market.stop();}
    }
    @Test void actualSourceMissingMinuteInsidePageStopsBeforeLaterRowsAndPage() throws Exception {
        Fixture f=new Fixture();f.source(1000,"100.12345678",f.received);f.pump();assertTrue(f.project());assertTrue(f.project());
        ForexQuoteMarketService market=consumerMarket(f);long end=f.first+1000*60000L-1;
        try {
            Map<String,Object> complete=market.readSnapshot(()->market.historicalKline(f.config.getSymbol(),"1m",1000,end));
            assertEquals("available",complete.get("status"));assertEquals(sourceBars(f,0,1000,"100.12345678"),ControlHistoryStore.rows(complete));
            // Deliberate corruption of this newly synthetic derived fixture only, under the actual owned runtime/S4 fence.
            long[] ownedConnection={-1},cleanupConnection={-2};int[] cleared={0};Trace mutation=trace("actual-owned-S4-fixture-gap-and-lower-unused-cutoff");
            try {f.store.locked(f.id,()->{
                Map<String,Object> current=f.runtime();ownedConnection[0]=f.db.queryForObject("SELECT CONNECTION_ID()",Long.class);
                f.db.update("SET @mt705_s4_tenant=?,@mt705_s4_symbol=?,@mt705_s4_generation=?,@mt705_s4_revision=?",TENANT,f.id,number(current,"writer_generation"),number(current,"control_revision"));
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){f.db.execute("SET @mt705_s4_tenant=NULL,@mt705_s4_symbol=NULL,@mt705_s4_generation=NULL,@mt705_s4_revision=NULL");cleanupConnection[0]=f.db.queryForObject("SELECT CONNECTION_ID()",Long.class);cleared[0]=f.db.queryForObject("SELECT IF(@mt705_s4_tenant IS NULL AND @mt705_s4_symbol IS NULL AND @mt705_s4_generation IS NULL AND @mt705_s4_revision IS NULL,1,0)",Integer.class);}});
                assertEquals(1,f.db.update("DELETE FROM s4_history_projection_minute WHERE tenant_id=? AND symbol_id=? AND minute_at=? AND generation=?",TENANT,f.id,f.first+200*60000L,number(current,"writer_generation")));
                // The lower cutoff is deliberately after the gap and must never become the displayed prefix minimum.
                assertEquals(1,f.db.update("UPDATE s4_history_projection_minute SET received_cutoff=? WHERE tenant_id=? AND symbol_id=? AND minute_at=? AND generation=?",f.first-60000,TENANT,f.id,f.first+300*60000L,number(current,"writer_generation")));return null;
            });}finally{source.clear();}
            assertEquals(999,f.minutes().size());assertEquals(ownedConnection[0],cleanupConnection[0]);assertEquals(1,cleared[0]);assertEquals(1,mutation.connections);
            Map<String,Object> runtime=f.runtime(),progress=f.progress();List<Map<String,Object>> minutes=f.minutes(),raw=f.rawRows();
            long cutoff=f.db.queryForObject("SELECT MIN(received_cutoff) FROM s4_history_projection_minute WHERE tenant_id=? AND symbol_id=? AND minute_at>=? AND minute_at<?",Long.class,TENANT,f.id,f.first,f.first+200*60000L);
            Trace read=trace("actual-source-in-page-missing-minute-stops-contiguous-prefix");read.pureReads=true;Map<String,Object> response;
            try {response=market.readSnapshot(()->market.historicalKline(f.config.getSymbol(),"1m",1000,end));}finally{source.clear();}
            assertEquals("partial",response.get("status"));assertTrue(Boolean.TRUE.equals(((Map<?,?>)response.get("data")).get("pending")));assertEquals(sourceBars(f,0,200,"100.12345678"),ControlHistoryStore.rows(response));
            assertEquals(cutoff,((Number)((Map<?,?>)response.get("data")).get("historyReceivedCutoffMinimum")).longValue());assertPureConsumer(read);
            assertEquals(runtime,f.runtime());assertEquals(progress,f.progress());assertEquals(minutes,f.minutes());assertEquals(raw,f.rawRows());
        }finally{market.stop();}
    }
    @Test void actualSourceCleanFirstPageRemainsPrefixWhenSecondPageDirty() throws Exception {
        Fixture f=new Fixture();f.source(1000,"100.12345678",f.received);f.pump();assertTrue(f.project());assertTrue(f.project());
        ForexQuoteMarketService market=consumerMarket(f);long end=f.first+1000*60000L-1;
        try {
            Map<String,Object> complete=market.readSnapshot(()->market.historicalKline(f.config.getSymbol(),"1m",1000,end));
            assertEquals("available",complete.get("status"));assertEquals(sourceBars(f,0,1000,"100.12345678"),ControlHistoryStore.rows(complete));
            f.store.sourceCandles(f.id,"1m",Collections.singletonList(bar(f.first+750*60000L,"105.12345678")),f.received+1);
            Map<String,Object> runtime=f.runtime(),progress=f.progress();List<Map<String,Object>> minutes=f.minutes(),raw=f.rawRows();
            Trace read=trace("actual-source-clean-first500-second500-dirty-keeps-prefix");read.pureReads=true;Map<String,Object> response;
            try {response=market.readSnapshot(()->market.historicalKline(f.config.getSymbol(),"1m",1000,end));}finally{source.clear();}
            assertEquals("partial",response.get("status"));assertTrue(Boolean.TRUE.equals(((Map<?,?>)response.get("data")).get("pending")));assertEquals(sourceBars(f,0,500,"100.12345678"),ControlHistoryStore.rows(response));assertPureConsumer(read);
            assertEquals(runtime,f.runtime());assertEquals(progress,f.progress());assertEquals(minutes,f.minutes());assertEquals(raw,f.rawRows());
        }finally{market.stop();}
    }
    private static List<Map<String,Object>> sourceBars(Fixture f,int offset,int count,String price){List<Map<String,Object>> result=new ArrayList<>();for(int i=0;i<count;i++)result.add(bar(f.first+(offset+i)*60000L,price));return result;}
    @Test void exactAlreadyApplied0403ShapeIsReadOnlyVerifiedWithoutAnyDdlOrDataMutation() throws Exception {
        Map<String,List<String>> before=rows();Trace repeat=trace("checked-0403-exact-repeat-no-DDL");
        try {assertTrue(verifyComplete0403(plain));}finally{source.clear();}
        assertTrue(repeat.sql().stream().allMatch(sql->sql.startsWith("SELECT ")));
        assertEquals(before,rows());
    }
    @Test void actualLateInputAndFinalDirtyReceiptRollbackKeepBodyHashProgressCursorOnePhysicalTransaction() throws Exception {
        Fixture f=new Fixture();f.source(2,"100.12345678",f.received);f.pump();assertTrue(f.project());
        long water=number(f.progress(),"watermark");f.store.sourceCandles(f.id,"1m",Collections.singletonList(bar(f.first,"105.12345678")),f.received+1);
        assertEquals(2,number(f.runtime(),"source_input_revision"));assertTrue(f.derived.readSourceWindow(f.id,f.first,f.first).pending);
        Map<String,Object> runtime=f.runtime(),progress=f.progress();List<Map<String,Object>> minutes=f.minutes();
        Trace rollback=trace("dirty-cursor-after-real-update-rolls-back");f.db.cleanupTrace=rollback;f.db.failDirty=true;
        try {assertThrows(IllegalStateException.class,f::project);}finally{source.clear();f.db.failDirty=false;}
        assertEquals(runtime,f.runtime());assertEquals(progress,f.progress());assertEquals(minutes,f.minutes());
        assertEquals(1,rollback.connections);assertEquals(1,rollback.cleanupCleared);assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK,rollback.cleanupStatus);
        Trace committed=trace("same-source-input-body-hash-progress-dirty-commit");f.db.cleanupTrace=committed;
        try{assertTrue(f.project());}finally{source.clear();}
        assertEquals(1,committed.connections);assertEquals(1,committed.connectionIds.size());assertEquals(1,committed.cleanupCleared);assertEquals(TransactionSynchronization.STATUS_COMMITTED,committed.cleanupStatus);
        assertEquals(water,number(f.progress(),"watermark"));assertEquals(2,number(f.progress(),"fact_version"));assertEquals(2,number(f.progress(),"input_revision"));
        assertNotNull(f.progress().get("last_hash"));assertNotEquals(progress.get("last_hash"),f.progress().get("last_hash"));
        assertNull(f.runtime().get("source_dirty_from"));assertEquals(Collections.singletonList(bar(f.first,"105.12345678")),f.derived.readSourceWindow(f.id,f.first,f.first).minutes);
    }
    @Test void actualInputReceiptFailureRollsBackRawAndRevisionAndUnfencedDirtyDmlIsDenied() throws Exception {
        Fixture f=new Fixture();f.source(2,"100.12345678",f.received);f.pump();assertTrue(f.project());
        Map<String,Object> runtime=f.runtime(),progress=f.progress();List<Map<String,Object>> raw=f.rawRows(),minutes=f.minutes();
        Trace rollback=trace("source-body-and-input-receipt-rollback");f.db.cleanupTrace=rollback;f.db.failInput=true;
        try{assertThrows(IllegalStateException.class,()->f.store.sourceCandles(f.id,"1m",Collections.singletonList(bar(f.first,"105.12345678")),f.received+1));}
        finally{source.clear();f.db.failInput=false;}
        assertEquals(1,rollback.connections);assertEquals(1,rollback.cleanupCleared);assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK,rollback.cleanupStatus);assertEquals(runtime,f.runtime());assertEquals(progress,f.progress());assertEquals(raw,f.rawRows());assertEquals(minutes,f.minutes());
        Trace denied=trace("real-three-runtime-source-trigger-unfenced-dml-denied");
        try{
            denied(()->f.db.update("UPDATE market_engine_runtime SET source_input_revision=source_input_revision+1,source_dirty_from=?,source_dirty_to=? WHERE tenant_id=? AND symbol_id=?",f.first,f.first,TENANT,f.id),"Source revision writer fenced");
            denied(()->f.db.update("UPDATE market_engine_runtime SET source_input_revision=0 WHERE tenant_id=? AND symbol_id=?",TENANT,f.id),"Source revision writer fenced");
            denied(()->f.db.update("DELETE FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",TENANT,f.id),"Source revision receipt cannot be deleted");
            denied(()->f.db.update("INSERT INTO market_engine_runtime(tenant_id,symbol_id,source_input_revision,source_dirty_from,source_dirty_to) VALUES(?,?,1,?,?)",TENANT,f.id,f.first,f.first),"Source revision must start at zero");
            Trace accepted=new Trace("actual-SOURCE-input-commit-session-clean");traces.add(accepted);source.start(accepted);f.db.cleanupTrace=accepted;
            try{f.store.sourceCandles(f.id,"1m",Collections.singletonList(bar(f.first,"105.12345678")),f.received+1);}finally{source.start(denied);}
            assertEquals(1,accepted.connections);assertEquals(1,accepted.connectionIds.size());assertEquals(1,accepted.cleanupCleared);
            assertEquals(TransactionSynchronization.STATUS_COMMITTED,accepted.cleanupStatus);assertTrue(accepted.connectionIds.contains(accepted.cleanupConnection));
            Map<String,Object> dirty=f.runtime();
            denied(()->f.db.update("UPDATE market_engine_runtime SET source_dirty_from=NULL,source_dirty_to=NULL WHERE tenant_id=? AND symbol_id=?",TENANT,f.id),"fenced");
            assertEquals(dirty,f.runtime());
        }finally{source.clear();}
        assertEquals(5,denied.failures);assertEquals(progress,f.progress());assertEquals(minutes,f.minutes());
    }
    @Test void realRRAliasWaitMustCurrentReadPriorBodyAndNotLeaveAlreadyPublishedCacheStale() throws Exception {
        Fixture first=new Fixture(),second=new Fixture();first.source(1,"100.12345678",first.received);
        // Same real writer instance represents one engine; different physical threads still take row locks.
        ControlHistoryStore writer=first.store;writer.sourceCandles(second.id,"1m",Collections.singletonList(bar(second.first,"100.12345678")),first.received);
        SourceHistoryProjector adapter=new SourceHistoryProjector(writer,first.manager);
        Map<String,Object> start=second.runtime();assertTrue(adapter.project(second.id,(String)start.get("owner_id"),number(start,"writer_generation"),number(start,"control_revision")));
        CountDownLatch snapshot=new CountDownLatch(1),resume=new CountDownLatch(1);first.db.pauseSymbol=first.id;first.db.snapshot=snapshot;first.db.resume=resume;
        Trace background=new Trace("actual-RR-two-alias-original-payload");traces.add(background);
        ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"source0403-alias-worker"));
        Future<?> input=worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(TENANT)){source.start(background);try{writer.sourceCandles(Arrays.asList(first.id,second.id),"1m",Collections.singletonList(bar(first.first,"100.12345678")),first.received);}finally{source.clear();}}});
        try{
            assertTrue(snapshot.await(10,TimeUnit.SECONDS));
            background.runtimeAttempt=new CountDownLatch(1);
            long revision=writer.locked(second.id,()->{
                writer.sourceCandles(second.id,"1m",Collections.singletonList(bar(second.first,"105.12345678")),first.received+1);
                Map<String,Object> route=second.runtime();assertTrue(adapter.project(second.id,(String)route.get("owner_id"),number(route,"writer_generation"),number(route,"control_revision")));
                long inputRevision=number(second.runtime(),"source_input_revision");assertNull(second.runtime().get("source_dirty_from"));
                long holder=first.db.queryForObject("SELECT CONNECTION_ID()",Long.class);
                resume.countDown();
                try{assertTrue(background.runtimeAttempt.await(10,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int waits=0;
                while(waits==0 && System.nanoTime()<until){
                    waits=first.db.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_lock_waits w JOIN information_schema.innodb_trx r ON r.trx_id=w.requesting_trx_id JOIN information_schema.innodb_trx b ON b.trx_id=w.blocking_trx_id WHERE r.trx_mysql_thread_id=? AND b.trx_mysql_thread_id=?",Integer.class,background.runtimeConnection,holder);
                    if(waits==0)try{Thread.sleep(20);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                }
                assertTrue(waits>0,"Actual second alias runtime lock wait required");assertFalse(input.isDone());
                background.event(holder,"actual-lock-wait-observed-before-holder-commit",null,Collections.emptyMap());
                return inputRevision;
            });
            input.get(10,TimeUnit.SECONDS);
            assertEquals(revision+1,number(second.runtime(),"source_input_revision"));assertNotNull(second.runtime().get("source_dirty_from"));
            assertTrue(second.derived.readSourceWindow(second.id,second.first,second.first).pending);
            Map<String,Object> route=second.runtime();assertTrue(adapter.project(second.id,(String)route.get("owner_id"),number(route,"writer_generation"),number(route,"control_revision")));
            assertEquals(Collections.singletonList(bar(second.first,"100.12345678")),second.derived.readSourceWindow(second.id,second.first,second.first).minutes);
            assertEquals(1,background.connections);assertTrue(background.sql().stream().anyMatch(sql->sql.startsWith("SELECT candle_at,body,received_at")&&sql.endsWith("FOR UPDATE")));
        }finally{resume.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));first.db.pauseSymbol=0;}
    }
    @Test void oneActualInputRevisionSupportsThree500BlocksAndSourceReaderRejectsNaturalTakeoverOldGeneration() throws Exception {
        Fixture f=new Fixture();f.source(1001,"100.12345678",f.received);f.pump();
        for(int version=1;version<=3;version++){assertTrue(f.project());assertEquals(version,number(f.progress(),"fact_version"));assertEquals(1,number(f.progress(),"input_revision"));}
        assertEquals(1001,f.minutes().size());Map<String,Object> old=f.runtime();assertNull(old.get("source_dirty_from"));assertFalse(f.project());
        long wait=number(old,"lease_until")-f.store.runtime.clock()+100;if(wait>0)Thread.sleep(wait);
        ControlHistoryStore successor=new ControlHistoryStore(f.db,f.manager);
        successor.sourceCandles(f.id,"1m",Collections.singletonList(bar(f.first,"105.12345678")),f.received+1);
        assertEquals(number(old,"writer_generation")+1,number(f.runtime(),"writer_generation"));
        MinuteHistoryProjectionStore.ReadResult stale=f.derived.readSourceWindow(f.id,f.first,f.first);assertTrue(stale.pending);assertTrue(stale.minutes.isEmpty());
        assertThrows(IllegalStateException.class,()->f.adapter.project(f.id,(String)old.get("owner_id"),number(old,"writer_generation"),number(old,"control_revision")));
        Map<String,Object> current=f.runtime();assertTrue(f.adapter.project(f.id,(String)current.get("owner_id"),number(current,"writer_generation"),number(current,"control_revision")));
        assertFalse(f.derived.readSourceWindow(f.id,f.first,f.first).pending);
        assertEquals(Collections.singletonList(bar(f.first,"105.12345678")),f.derived.readSourceWindow(f.id,f.first,f.first).minutes);
    }
    /** Native certificate check: invoke in one real read-only RR TransactionTemplate before any synthetic INSERT.
     * expectedData is immutable after-ddl-after.json.data, after verifying its separate required SHA256.
     * Same native HEX(CAST AS BINARY) / JSON_ARRAY / SHA2 / sorted-newline recipe as mysql_migration.fingerprint.
     */
    static void requireNativeBaselineFingerprint(JdbcTemplate db,com.fasterxml.jackson.databind.JsonNode expectedData) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),"Baseline requires actual RR physical transaction");
        db.execute((Connection connection)->{assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());assertTrue(connection.isReadOnly());return null;});
        assertTrue(expectedData.isObject());assertTrue(expectedData.path("columns").isObject());assertTrue(expectedData.path("tables").isObject());
        Map<String,List<String>> actualColumns=new TreeMap<>();
        for(Map<String,Object> row:db.queryForList("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position"))
            actualColumns.computeIfAbsent((String)row.get("table_name"),ignored->new ArrayList<>()).add((String)row.get("column_name"));
        Map<String,List<String>> frozenColumns=new TreeMap<>();
        for(Iterator<Map.Entry<String,com.fasterxml.jackson.databind.JsonNode>> entries=expectedData.path("columns").fields();entries.hasNext();){
            Map.Entry<String,com.fasterxml.jackson.databind.JsonNode> entry=entries.next();assertTrue(entry.getValue().isArray());
            List<String> columns=new ArrayList<>();for(com.fasterxml.jackson.databind.JsonNode column:entry.getValue()){assertTrue(column.isTextual());columns.add(column.textValue());}
            frozenColumns.put(entry.getKey(),columns);
        }
        assertEquals(frozenColumns,actualColumns,"Exact all-table/all-column/ordinal scope changed; no omitted columns");
        Set<String> tableKeys=new TreeSet<>();expectedData.path("tables").fieldNames().forEachRemaining(tableKeys::add);
        assertEquals(frozenColumns.keySet(),tableKeys,"Fingerprint scope incomplete");
        assertEquals(frozenColumns.keySet(),new TreeSet<>(db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name",String.class)),"Exact base-table inventory changed");
        for(Map.Entry<String,List<String>> entry:frozenColumns.entrySet()){
            assertTrue(entry.getKey().matches("[A-Za-z0-9_]+"));assertFalse(entry.getValue().isEmpty());
            List<String> values=new ArrayList<>();for(String column:entry.getValue()){assertTrue(column.matches("[A-Za-z0-9_]+"));values.add("HEX(CAST(`"+column+"` AS BINARY))");}
            List<String> hashes=new ArrayList<>(db.queryForList("SELECT SHA2(JSON_ARRAY("+String.join(",",values)+"),256) FROM `"+entry.getKey()+"`",String.class));
            for(String hash:hashes)assertTrue(hash!=null&&hash.matches("[0-9a-f]{64}"),"Invalid native row hash");
            Collections.sort(hashes);com.fasterxml.jackson.databind.JsonNode wanted=expectedData.path("tables").path(entry.getKey());
            assertTrue(wanted.path("rows").isIntegralNumber());assertTrue(wanted.path("sha256").isTextual());
            assertEquals(wanted.path("rows").longValue(),(long)hashes.size(),"Native baseline row count: "+entry.getKey());
            try{
                byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n",hashes).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(Locale.ROOT,"%02x",value&255));
                assertEquals(wanted.path("sha256").textValue(),hex.toString(),"Native all-column multiset hash: "+entry.getKey());
            }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
    }

    static boolean verifyComplete0403(JdbcTemplate db) throws Exception {
        Map<String,String[]> expected=new TreeMap<>();
        expected.put("market_engine_runtime.source_input_revision",new String[]{"bigint(20)","NO","0"});
        expected.put("market_engine_runtime.source_dirty_from",new String[]{"bigint(20)","YES",null});
        expected.put("market_engine_runtime.source_dirty_to",new String[]{"bigint(20)","YES",null});
        expected.put("s4_history_projection_progress.input_revision",new String[]{"bigint(20)","NO","0"});
        for(Map.Entry<String,String[]> entry:expected.entrySet()){
            String[] name=entry.getKey().split("\\.");
            List<Map<String,Object>> rows=db.queryForList("SELECT column_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",name[0],name[1]);
            assertEquals(1,rows.size(),entry.getKey());Map<String,Object> actual=rows.get(0);
            assertEquals(entry.getValue()[0],actual.get("column_type"));assertEquals(entry.getValue()[1],actual.get("is_nullable"));assertEquals(entry.getValue()[2],actual.get("column_default"));
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM tenant_schema_version WHERE version=2026100403 AND minimum_application_epoch=2026100403 AND business_activation_ready=0",Integer.class));
        String formal=resource("/db/migration/V2026100403__source_history_input_revision.sql");
        String prior=resource("/db/migration/V2026100402__joint_s4_history_projection.sql");
        Map<String,String> bodies=new TreeMap<>();
        for(String sql:Arrays.asList(prior,formal)){
            java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("CREATE TRIGGER\\s+([A-Za-z0-9_]+)\\s+(?:.*?)FOR EACH ROW\\s*(BEGIN.*?END)\\$\\$",java.util.regex.Pattern.DOTALL).matcher(sql);
            while(matcher.find())assertNull(bodies.put(matcher.group(1),matcher.group(2).trim()));
        }
        assertEquals(9,bodies.size(),"Exact frozen six0402 plus three0403 trigger bodies required");
        for(Map.Entry<String,String> entry:bodies.entrySet()){
            List<String> actual=db.queryForList("SELECT action_statement FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=?",String.class,entry.getKey());
            assertEquals(1,actual.size());assertEquals(entry.getValue(),actual.get(0).replace("\r\n","\n").trim(),entry.getKey());
        }
        return true;
    }
    private static String resource(String name) throws Exception {
        try(java.io.InputStream input=SourceDirty0403MySqlIT.class.getResourceAsStream(name)){
            assertNotNull(input,name);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1){assertTrue(bytes.size()+n<=128*1024);bytes.write(buffer,0,n);}
            return new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n");
        }
    }
    private Trace trace(String name){Trace trace=new Trace(name);traces.add(trace);source.start(trace);return trace;}
    private static void denied(Runnable sql,String text){RuntimeException e=assertThrows(RuntimeException.class,sql::run);Throwable root=e;while(root.getCause()!=null)root=root.getCause();assertTrue(root.getMessage().contains(text),root.getMessage());}
    private static long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
    private static Map<String,Object> bar(long at,String price){Map<String,Object> row=new LinkedHashMap<>();row.put("timestamp",at);for(String key:Arrays.asList("open_price","high_price","low_price","close_price"))row.put(key,new BigDecimal(price));row.put("volume",1);return row;}
    private static Map<String,List<String>> rows() throws Exception {
        Map<String,List<String>> rows=new TreeMap<>();for(String table:plain.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name",String.class)){
            assertTrue(table.matches("[A-Za-z0-9_]+"));List<String> values=new ArrayList<>();for(Map<String,Object> row:plain.queryForList("SELECT * FROM `"+table+"`"))values.add(JSON.writeValueAsString(new TreeMap<>(row)));Collections.sort(values);rows.put(table,values);
        }return rows;
    }
    private static final class Fixture {
        final long id=IDS.incrementAndGet(),first=MINUTE_BASE,received;
        final TradingSymbol config=new TradingSymbol();final ObservedJdbc db=new ObservedJdbc();
        final DataSourceTransactionManager manager=new DataSourceTransactionManager(source);final ControlHistoryStore store=new ControlHistoryStore(db,manager);
        final PersistentPriceControl controls=new PersistentPriceControl(store);final SourceHistoryProjector adapter=new SourceHistoryProjector(store,manager);
        final MinuteHistoryProjectionStore derived=new MinuteHistoryProjectionStore(db,manager);
        Fixture(){received=store.runtime.clock()-1000;config.setTenantId(TENANT);config.setId(id);config.setSymbol("SD"+id);config.setPricePrecision(8);config.setIsEnabled(true);config.setControlEnabled(false);config.setRandomMarketEnabled(false);config.setRowVersion(0);
            db.update("INSERT INTO trading_symbol(tenant_id,id,symbol,name,base_currency,quote_currency,market_source,source_category,category,is_enabled,control_enabled,random_market_enabled,price_precision,row_version) VALUES(?,?,?,?,'TEST','USD','yahoo','Metal','Metal',1,0,0,8,0)",TENANT,id,config.getSymbol(),config.getSymbol());}
        void source(int count,String price,long received){List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add(bar(first+i*60000L,price));store.sourceCandles(id,"1m",rows,received);}
        void pump(){long now=store.runtime.clock();Map<String,Object> raw=new LinkedHashMap<>();raw.put("price",new BigDecimal("100.12345678"));raw.put("timestamp",now);raw.put("sourceTimestamp",now);raw.put("available",true);raw.put("expiresAt",now+60000);controls.pump(config,raw,now,60000);}
        Map<String,Object> runtime(){return db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",TENANT,id);}
        Map<String,Object> progress(){return db.queryForMap("SELECT * FROM s4_history_projection_progress WHERE tenant_id=? AND symbol_id=?",TENANT,id);}
        List<Map<String,Object>> minutes(){return db.queryForList("SELECT * FROM s4_history_projection_minute WHERE tenant_id=? AND symbol_id=? ORDER BY minute_at",TENANT,id);}
        List<Map<String,Object>> rawRows(){return db.queryForList("SELECT * FROM market_source_candle WHERE tenant_id=? AND symbol_id=? ORDER BY period,candle_at",TENANT,id);}
        boolean project(){return store.transaction(()->{Map<String,Object> r=runtime();return adapter.project(id,(String)r.get("owner_id"),number(r,"writer_generation"),number(r,"control_revision"));});}
    }
    private static final class ObservedJdbc extends JdbcTemplate {
        volatile boolean failDirty,failInput;volatile long pauseSymbol;CountDownLatch snapshot,resume;Trace cleanupTrace;
        ObservedJdbc(){super(source);}
        @Override public int update(String sql,Object...arguments){int result=super.update(sql,arguments);
            if((sql.startsWith("INSERT INTO s4_history_projection_minute")||sql.startsWith("UPDATE market_engine_runtime SET source_input_revision="))&&cleanupTrace!=null&&!cleanupTrace.cleanupRegistered){final Trace t=cleanupTrace;t.cleanupRegistered=true;TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){t.cleanupStatus=status;t.cleanupCleared=queryForObject("SELECT IF(@mt705_s2_owner IS NULL AND @mt705_s2_fences IS NULL AND @mt705_s4_tenant IS NULL AND @mt705_s4_symbol IS NULL AND @mt705_s4_generation IS NULL AND @mt705_s4_revision IS NULL,1,0)",Integer.class);t.cleanupConnection=queryForObject("SELECT CONNECTION_ID()",Long.class);}});}
            if(failDirty&&sql.startsWith("UPDATE market_engine_runtime SET source_dirty_from="))throw new IllegalStateException("after dirty receipt");
            if(failInput&&sql.startsWith("UPDATE market_engine_runtime SET source_input_revision="))throw new IllegalStateException("after input receipt");
            if(Thread.currentThread().getName().equals("source0403-alias-worker")&&pauseSymbol>0&&sql.startsWith("INSERT INTO market_source_candle")&&((Number)arguments[0]).longValue()==pauseSymbol){assertEquals("REPEATABLE-READ",queryForObject("SELECT @@tx_isolation",String.class));snapshot.countDown();try{if(!resume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("alias race barrier timed out");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
            return result;
        }
    }
    private static final class Trace {
        final String name; final List<Map<String,Object>> events = new CopyOnWriteArrayList<>();
        final Set<Long> connectionIds = new ConcurrentSkipListSet<>();
        int connections, failures, cleanupStatus = -1, cleanupCleared = -1;
        boolean cleanupRegistered; long commitStartedAt, commitAt, firstRevisionWriteAt; Long runtimeConnection, cleanupConnection;
        CountDownLatch runtimeAttempt,sourceReadCaptured,sourceReadResume;
        CountDownLatch wsReadCaptured,wsReadResume;Long wsReadSymbol;
        boolean pureReads;
        Runnable beforeCommitCheck;
        int verifiedReadOnlyRRStatements;
        Trace(String name) { this.name = name; }
        void event(long connection, String kind, String sql, Map<Integer,Object> binds) {
            Map<String,Object> event = new LinkedHashMap<>(); event.put("connection_id", connection); event.put("kind", kind); event.put("at_nanos", System.nanoTime());
            if (sql != null) { event.put("sql", sql); event.put("arguments", new TreeMap<>(binds)); }
            events.add(event);
        }
        List<String> sql() {
            List<String> sql = new ArrayList<>(); for (Map<String,Object> event : events) if (event.get("kind").equals("execute")) sql.add(String.valueOf(event.get("sql"))); return sql;
        }
        Map<String,Object> evidence() {
            Map<String,Object> evidence = new LinkedHashMap<>(); evidence.put("name", name); evidence.put("opened_physical_connections", connections);
            evidence.put("connection_ids", connectionIds); evidence.put("statement_failures", failures); evidence.put("commit_requested_at_nanos", commitStartedAt); evidence.put("commit_ack_at_nanos", commitAt);
            evidence.put("first_stop_revision_write_at_nanos", firstRevisionWriteAt); evidence.put("cleanup_status", cleanupStatus);
            evidence.put("cleanup_variables_null", cleanupCleared == 1); evidence.put("cleanup_connection_id", cleanupConnection); evidence.put("events", events); return evidence;
        }
    }

    /** Records real Statements and CONNECTION_ID; never substitutes connection, locking, writer or guard behavior. */
    private static final class RecordingSource extends AbstractDataSource {
        final DriverManagerDataSource verified; final ThreadLocal<Trace> current = new ThreadLocal<>();
        volatile Trace backgroundSourceWriter;
        RecordingSource(DriverManagerDataSource verified) { this.verified = verified; }
        void start(Trace trace) { current.set(trace); } void clear() { current.remove(); }
        @Override public Connection getConnection() throws SQLException { return recording(verified.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException { return recording(verified.getConnection(username, password)); }
        Connection recording(Connection raw) throws SQLException {
            Trace selected = current.get(); if (selected == null && Thread.currentThread().getName().equals("market-Crypto")) selected=backgroundSourceWriter;
            final Trace trace=selected; if (trace == null) return raw;
            long id;
            try (Statement statement = raw.createStatement(); ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) { assertTrue(result.next()); id = result.getLong(1); }
            trace.connections++; trace.connectionIds.add(id); trace.event(id, "connection-open", "SELECT CONNECTION_ID()", Collections.emptyMap());
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("commit")) { trace.commitStartedAt = System.nanoTime(); trace.event(id, "commit-call", null, Collections.emptyMap());if(trace.beforeCommitCheck!=null){trace.beforeCommitCheck.run();trace.event(id,"before-commit-check-completed",null,Collections.emptyMap());} }
                Object result = invoke(raw, method, args);
                if (method.getName().equals("commit")) { trace.commitAt = System.nanoTime(); trace.event(id, "commit", null, Collections.emptyMap()); }
                if (method.getName().equals("rollback")) trace.event(id, "rollback", null, Collections.emptyMap());
                if (result instanceof Statement && (method.getName().equals("prepareStatement") || method.getName().equals("createStatement"))) {
                    Statement statement = (Statement) result; String prepared = method.getName().equals("prepareStatement") ? String.valueOf(args[0]) : null;
                    Map<Integer,Object> binds = new TreeMap<>(); Class<?> kind = prepared == null ? Statement.class : PreparedStatement.class;
                    return Proxy.newProxyInstance(kind.getClassLoader(), new Class<?>[]{kind}, (ignored, operation, values) -> {
                        if (operation.getName().startsWith("set") && values != null && values.length >= 2 && values[0] instanceof Integer) binds.put((Integer) values[0], operation.getName().equals("setNull") ? null : values[1]);
                        boolean execute = operation.getName().startsWith("execute");
                        String sql = prepared == null && execute && values != null && values.length > 0 ? String.valueOf(values[0]) : prepared;
                        if (execute && sql != null) {
                            trace.event(id, "execute", sql, binds);
                            if(trace.pureReads) {
                                assertFalse(raw.getAutoCommit());assertTrue(raw.isReadOnly());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,raw.getTransactionIsolation());
                                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                                try(Statement mode=raw.createStatement();ResultSet read=mode.executeQuery("SELECT @@tx_read_only,@@tx_isolation")) {
                                    assertTrue(read.next());assertEquals(1,read.getInt(1));assertEquals("REPEATABLE-READ",read.getString(2));
                                    trace.event(id,"execute","SELECT @@tx_read_only,@@tx_isolation",Collections.emptyMap());
                                }
                                trace.verifiedReadOnlyRRStatements++;
                            }
                            if (sql.startsWith("INSERT INTO market_engine_runtime") && trace.runtimeAttempt != null) { trace.runtimeConnection = id; trace.runtimeAttempt.countDown(); }
                        }
                        try {
                            Object value = invoke(statement, operation, values);
                            if (execute && sql != null) {
                                if (sql.startsWith("UPDATE market_engine_runtime SET control_revision=control_revision+1") && trace.firstRevisionWriteAt == 0) trace.firstRevisionWriteAt = System.nanoTime();
                                trace.event(id, "execute-return", sql, binds);
                                if(trace.wsReadCaptured!=null && WS_RUNTIME_SQL.equals(sql)) {
                                    trace.wsReadSymbol=((Number)binds.get(2)).longValue();CountDownLatch captured=trace.wsReadCaptured;trace.wsReadCaptured=null;captured.countDown();
                                    if(!trace.wsReadResume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("WS tenant RR race timed out");
                                }
                                if(trace.sourceReadCaptured!=null && sql.startsWith("SELECT generation,fact_version,initial_watermark")) {
                                    CountDownLatch captured=trace.sourceReadCaptured;trace.sourceReadCaptured=null;captured.countDown();
                                    if(!trace.sourceReadResume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Consumer RR race timed out");
                                }
                            }
                            return value;
                        } catch (Throwable failure) {
                            if (execute) { trace.failures++; trace.event(id, "execute-failed", sql, binds); }
                            throw failure;
                        }
                    });
                }
                return result;
            });
        }
    }
    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
