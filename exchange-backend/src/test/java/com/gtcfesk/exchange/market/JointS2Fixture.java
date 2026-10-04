package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.tenant.DedicatedMysqlFixture;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Test-only transport for this joint task. Never widens the old S2 fixture contract. */
final class JointS2Fixture {
    static final String OWNER="34c425ba-b9f3-4da6-b06f-b6be6e88a059", RUN="stage1-20261004-045201";
    static final String JOINT_RUN="joint-20261004-045201-34c425ba";
    static final String MYSQL="0d9216dab0cd7953239369c76db86826f249994a4232bbf7bc84e19044e7e901";
    static final String RESTORE="4d1559abeb387aefcd1a363981a5ea66ce48a822bd33284093ed15b02e6741ef";
    static final String REDIS="210e762750255aa8ffce6c2ad80a927cd7f83461eb03a7a178bafff56219bbc5";
    static final String MYSQL_UUID="4b3cd395-bf6c-11f1-aaa9-2a4a23a9f505";
    static final String MYSQL_IMAGE="sha256:4bc6bc963e6d8443453676cae56536f4b8156d78bae03c0145cbe47c2aad73bb";
    static final String REDIS_IMAGE="sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499";
    static final String REDIS_VOLUME="db0f9b378083ad4cb06b83c6df9ba45945d7af06ce19218566d4a30d452d2e2c";
    private static final ObjectMapper JSON=new ObjectMapper();
    final Path path; final String sha; final Map<String,String> settings;
    final JsonNode resources, redisResources, proof;
    final Path redisConfig, redisPassword;
    final DriverManagerDataSource data;
    final Map<String,Object> fixture=new LinkedHashMap<>();

    static JointS2Fixture open(boolean initial) throws Exception {
        String value=System.getProperty("joint.s2.fixture");
        if(value==null||value.isBlank())throw new IllegalArgumentException("Explicit joint.s2.fixture required");
        if(System.getenv("S2_FIXTURE_FILE")!=null)throw new IllegalArgumentException("Joint and old S2 identities cannot be combined");
        return new JointS2Fixture(Paths.get(value),initial);
    }
    private JointS2Fixture(Path input,boolean initial) throws Exception {
        path=input.toAbsolutePath().normalize(); checkedFile(path,64*1024); sha=DedicatedMysqlFixture.hash(path);
        settings=JSON.readValue(Files.readAllBytes(path),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});
        validateSettings(settings);
        resources=readEvidence(settings,"jointResources",192*1024); redisResources=readEvidence(settings,"redisResources",64*1024);
        require(OWNER.equals(resources.path("owner").asText())&&RUN.equals(resources.path("run").asText())&&JOINT_RUN.equals(resources.path("jointRun").asText()),"Joint resource owner/run changed");
        require(OWNER.equals(redisResources.path("owner").asText())&&RUN.equals(redisResources.path("run").asText())&&JOINT_RUN.equals(redisResources.path("jointRun").asText()),"Redis resource owner/run changed");
        require(REDIS.equals(redisResources.path("id").asText())&&redisResources.path("port").asInt()==33319,"Redis resource identity changed");
        proof=readEvidence(settings,"fixtureProof",192*1024);
        require(OWNER.equals(proof.path("source").path("owner").asText())&&OWNER.equals(proof.path("independent_restore_before_ddl").path("target").path("owner").asText()),"Restore proof not owned by this task");
        JsonNode sourceIdentity=proof.path("source"),restoreIdentity=proof.path("independent_restore_before_ddl").path("target");
        require(MYSQL.equals(sourceIdentity.path("container_id").asText())&&RESTORE.equals(restoreIdentity.path("container_id").asText())&&MYSQL_UUID.equals(sourceIdentity.path("server_uuid").asText())&&"4bd6fbfe-bf6c-11f1-8a15-e6c395605aea".equals(restoreIdentity.path("server_uuid").asText()),"Exact retained source/restore full IDs and distinct UUIDs required");
        require(sourceIdentity.path("database").asText().equals(settings.get("url").split("/",4)[3].split("\\?",2)[0])&&(sourceIdentity.path("database").asText()+"_restore").equals(restoreIdentity.path("database").asText()),"Exact dedicated source and independent restore namespaces required");
        require(sourceIdentity.path("volume").asText().equals(resources.path("resources").path("source").path("volume").asText())&&restoreIdentity.path("volume").asText().equals(resources.path("resources").path("restore").path("volume").asText())&&!sourceIdentity.path("volume").asText().equals(restoreIdentity.path("volume").asText()),"Exact independent owned volumes required");
        require(proof.path("independent_restore_before_ddl").path("schema_equal").asBoolean()&&proof.path("all_columns_and_control_included").asBoolean(),"Complete native restore proof required");
        Path home=Paths.get(resources.path("privateHome").asText()).toAbsolutePath().normalize();
        redisConfig=home.resolve("redis/redis.conf"); redisPassword=home.resolve("redis/password");
        checkedFile(redisConfig,16*1024); checkedFile(redisPassword,128);
        require(DedicatedMysqlFixture.hash(redisConfig).equals(redisResources.path("configurationSha256").asText()),"Redis configuration changed");
        String password=Files.readString(redisPassword,StandardCharsets.UTF_8).trim(); require(password.matches("[a-f0-9]{64}"),"Owned Redis password required");
        Set<String> lines=new HashSet<>(Files.readAllLines(redisConfig,StandardCharsets.UTF_8));
        require(lines.containsAll(Arrays.asList("requirepass "+password,"protected-mode yes","port 6379","maxmemory 128mb","maxmemory-policy noeviction","save \"\"","appendonly no")),"Owned cache-only Redis configuration changed");
        verifyContainer("mysql",true); verifyContainer("restore",true); verifyContainer("redis",true);
        data=DedicatedMysqlFixture.open(settings); // Actual separate-UUID restore and unchanged backup proof, no port-only shortcut.
        JdbcTemplate db=new JdbcTemplate(data);
        require(db.queryForObject("SELECT COUNT(*) FROM tenant_schema_version WHERE version=? AND minimum_application_epoch=? AND business_activation_ready=0",Integer.class,schemaEpoch(),schemaEpoch())==1,"Inactive exact formal epoch receipt required");
        require(db.queryForObject("SELECT MAX(minimum_application_epoch) FROM tenant_schema_version",Long.class)==schemaEpoch(),"Exact final schema epoch required");
        require(db.queryForObject("SELECT SUM(business_activation_ready) FROM tenant_schema_version",Integer.class)==0,"Business activation forbidden");
        require(db.queryForObject("SELECT @@max_connections",Integer.class)==50&&db.queryForObject("SELECT @@innodb_buffer_pool_size",Long.class)==134217728L,"Owned MySQL limits changed");
        org.springframework.data.redis.connection.RedisStandaloneConfiguration redis=new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",33319);redis.setPassword(password);
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory auth=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(redis,org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder().commandTimeout(java.time.Duration.ofSeconds(1)).build());auth.afterPropertiesSet();
        try(org.springframework.data.redis.connection.RedisConnection ping=auth.getConnection()){require("PONG".equals(ping.ping()),"Actual owned Redis authentication failed");}finally{auth.destroy();}
        if(initial)verifyInitialState(db);
        fixture.put("owner",OWNER);fixture.put("run",RUN);fixture.put("mysql_port",33318);fixture.put("redis_port",33319);
        fixture.put("database",proof.path("source").path("database").asText());fixture.put("jdbc",settings.get("url"));fixture.put("username",settings.get("username"));fixture.put("password",settings.get("password"));
        fixture.put("server_uuid",MYSQL_UUID);fixture.put("redis_password",password);fixture.put("container_ids",Map.of("mysql",MYSQL,"redis",REDIS));
    }
    long schemaEpoch(){return Long.parseLong(settings.get("jointS2SchemaEpoch"));}
    static void validateSettings(Map<String,String> value) {
        require("docker".equals(value.get("fixtureTransport"))&&RUN.equals(value.get("runId"))&&OWNER.equals(value.get("jointOwner"))&&JOINT_RUN.equals(value.get("jointRun")),"Explicit current joint identity required");
        require(MYSQL.equals(value.get("containerId"))&&("mt705-"+RUN+"-mysql").equals(value.get("containerName"))&&MYSQL_UUID.equals(value.get("serverUuid")),"Current full MySQL ID/UUID required");
        String epoch=value.get("jointS2SchemaEpoch");require("2026100403".equals(epoch)||"2026100404".equals(epoch),"Only explicit403 or404 S2 fixture contract");
        String suffix="2026100404".equals(epoch)?"404":"403";
        require(value.get("url")!=null&&value.get("url").matches("jdbc:mysql://127\\.0\\.0\\.1:33318/mt705_probe_joint_s2_"+suffix+"_[a-f0-9]{16}\\?.*"),"Fresh exact-epoch dedicated joint S2 namespace required");
        require(value.get("username")!=null&&!value.get("username").isBlank()&&!"root".equals(value.get("username"))&&value.get("password")!=null&&!value.get("password").isBlank(),"Dedicated test account required");
        for(String key:Arrays.asList("jointResources","redisResources","fixtureProof"))require(value.get(key)!=null&&!value.get(key).isBlank()&&value.get(key+"Sha256")!=null&&value.get(key+"Sha256").matches("[a-f0-9]{64}"),"Hashed private identity/evidence file required: "+key);
    }
    void verifyUnchanged() throws Exception {
        require(path.equals(Paths.get(System.getProperty("joint.s2.fixture")).toAbsolutePath().normalize())&&sha.equals(DedicatedMysqlFixture.hash(path)),"Joint S2 identity changed within JVM");
        require(resources.equals(readEvidence(settings,"jointResources",192*1024))&&redisResources.equals(readEvidence(settings,"redisResources",64*1024))&&proof.equals(readEvidence(settings,"fixtureProof",192*1024)),"Sealed fixture dependencies changed");
        require(DedicatedMysqlFixture.hash(redisConfig).equals(redisResources.path("configurationSha256").asText())&&fixture.get("redis_password").equals(Files.readString(redisPassword,StandardCharsets.UTF_8).trim()),"Redis credentials/configuration changed");
        verifyContainer("mysql",true);verifyContainer("restore",true);verifyContainer("redis",true);
        require(MYSQL_UUID.equals(new JdbcTemplate(data).queryForObject("SELECT @@server_uuid",String.class)),"Live source UUID changed");
    }
    String verifyContainer(String kind,boolean running) throws Exception {
        require(Set.of("mysql","restore","redis").contains(kind),"Unknown fixture resource");
        boolean redis="redis".equals(kind), restore="restore".equals(kind);
        String id=redis?REDIS:restore?RESTORE:MYSQL;
        JsonNode expected=redis?redisResources:resources.path("resources").path(restore?"restore":"source");
        require(id.equals(expected.path("id").asText()),"Retained full resource ID changed");
        JsonNode row=single(command("docker","inspect","--type","container",id)), labels=row.path("Config").path("Labels"),host=row.path("HostConfig");
        require(id.equals(row.path("Id").asText())&&("/"+expected.path("name").asText()).equals(row.path("Name").asText())&&row.path("State").path("Running").asBoolean()==running,"Live resource identity/state changed");
        require(OWNER.equals(labels.path("com.gtcfesk.joint.owner").asText())&&JOINT_RUN.equals(labels.path("com.gtcfesk.joint.run").asText())&&RUN.equals(labels.path("com.gtcfesk.stage1.run").asText())&&"true".equals(labels.path("com.gtcfesk.multitenant.test").asText()),"Live joint owner/run mismatch");
        require((redis?"redis:7-alpine":"mysql:5.7").equals(row.path("Config").path("Image").asText())&&(redis?REDIS_IMAGE:MYSQL_IMAGE).equals(row.path("Image").asText()),"Pinned fixture image changed");
        long memory=redis?268435456L:1073741824L;
        require(host.path("Memory").asLong()==memory&&host.path("MemorySwap").asLong()==memory&&host.path("NanoCpus").asLong()==1000000000L&&host.path("PidsLimit").asInt()==128,"Fixture resource budget changed");
        require(row.path("NetworkSettings").path("Networks").size()==1&&row.path("NetworkSettings").path("Networks").has(resources.path("network").asText()),"Exclusive joint network changed");
        for(int portIndex=0;portIndex<2;portIndex++){
            JsonNode ports=portIndex==0?host.path("PortBindings"):row.path("NetworkSettings").path("Ports");
            Map<String,JsonNode> published=new TreeMap<>();ports.fields().forEachRemaining(entry->{if(!entry.getValue().isNull()&&!entry.getValue().isEmpty())published.put(entry.getKey(),entry.getValue());});
            if(restore||(portIndex==1&&!running))require(published.isEmpty(),restore?"Restore must not publish ports":"Stopped resource must not publish live ports");
            else {String internal=redis?"6379/tcp":"3306/tcp";int port=redis?33319:33318;JsonNode bindings=published.get(internal);
                require(published.size()==1&&bindings!=null&&bindings.isArray()&&bindings.size()==1&&"127.0.0.1".equals(bindings.get(0).path("HostIp").asText())&&String.valueOf(port).equals(bindings.get(0).path("HostPort").asText()),"Exact owned loopback binding required");}
        }
        JsonNode mounts=row.path("Mounts");require(mounts.isArray()&&mounts.size()==(redis?2:1),"Unexpected fixture mounts");
        String volume=redis?REDIS_VOLUME:expected.path("volume").asText();String destination=redis?"/data":"/var/lib/mysql";
        require(redis||volume.equals("mt705-"+JOINT_RUN+"-"+(restore?"restore":"source")+"-data"),"Owned MySQL volume changed");
        JsonNode volumeMount=null,configMount=null;for(JsonNode mount:mounts){if(destination.equals(mount.path("Destination").asText()))volumeMount=mount;else configMount=mount;}
        require(volumeMount!=null&&"volume".equals(volumeMount.path("Type").asText())&&volume.equals(volumeMount.path("Name").asText())&&volumeMount.path("RW").asBoolean(),"Exact writable owned data volume required");
        JsonNode info=single(command("docker","volume","inspect",volume));
        require(volume.equals(info.path("Name").asText())&&"local".equals(info.path("Driver").asText())&&(info.path("Options").isNull()||info.path("Options").isEmpty())&&info.path("Mountpoint").asText().equals(volumeMount.path("Source").asText())&&info.path("Mountpoint").asText().equals("/var/lib/docker/volumes/"+volume+"/_data"),"Local independent volume identity changed");
        if(redis)require(configMount!=null&&"bind".equals(configMount.path("Type").asText())&&!configMount.path("RW").asBoolean()&&"/usr/local/etc/redis/redis.conf".equals(configMount.path("Destination").asText())&&redisConfig.toRealPath().equals(Paths.get(configMount.path("Source").asText()).toRealPath()),"Owned readonly Redis configuration mount changed");
        else require(OWNER.equals(info.path("Labels").path("com.gtcfesk.joint.owner").asText())&&RUN.equals(info.path("Labels").path("com.gtcfesk.stage1.run").asText())&&JOINT_RUN.equals(info.path("Labels").path("com.gtcfesk.joint.run").asText()),"Owned MySQL volume labels changed");
        return id;
    }
    private void verifyInitialState(JdbcTemplate db) throws Exception {
        boolean final404=schemaEpoch()==2026100404L;int tables=final404?112:110;
        JsonNode sealed=proof.path("certified_initial_state");require(sealed.isObject(),"Sealed fresh0403 all-column initial state required");
        Path state=Paths.get(sealed.path("path").asText());checkedFile(state,2*1024*1024);require(DedicatedMysqlFixture.hash(state).equals(sealed.path("sha256").asText()),"Initial state seal changed");
        JsonNode frozen=JSON.readTree(Files.readAllBytes(state)).path("data");require(frozen.path("columns").isObject()&&frozen.path("tables").isObject()&&frozen.path("tables").size()==tables,"Full110 table scope required");
        List<String> actual=db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name",String.class);
        Set<String> names=new TreeSet<>();frozen.path("tables").fieldNames().forEachRemaining(names::add);require(new TreeSet<>(actual).equals(names)&&names.size()==tables,"Exact base-table inventory changed");
        long rows=0;
        for(String table:names){
            require(table.matches("[A-Za-z0-9_]+"),"Invalid sealed table name");List<String> columns=db.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table);List<String> wanted=new ArrayList<>();for(JsonNode col:frozen.path("columns").path(table)){require(col.isTextual()&&col.asText().matches("[A-Za-z0-9_]+"),"Invalid sealed column");wanted.add(col.asText());}require(columns.equals(wanted)&&!columns.isEmpty(),"Exact all-column ordinal scope changed");
            List<String> fields=new ArrayList<>();for(String col:columns)fields.add("HEX(CAST(`"+col+"` AS BINARY))");
            List<String> hashes=db.queryForList("SELECT SHA2(JSON_ARRAY("+String.join(",",fields)+"),256) FROM `"+table+"`",String.class);for(String hash:hashes)require(hash!=null&&hash.matches("[a-f0-9]{64}"),"Invalid native row hash");Collections.sort(hashes);
            JsonNode expected=frozen.path("tables").path(table);require(expected.path("rows").asLong()==hashes.size()&&digest(String.join("\n",hashes).getBytes(StandardCharsets.UTF_8)).equals(expected.path("sha256").asText()),"Native full-column multiset changed: "+table);rows+=hashes.size();
        }
        if(final404){
            require(rows==17&&proof.path("full_rows").asInt()==17,"Exact new schema-only15 plus two owned S2 tenants required");
            JsonNode seed=proof.path("local_s2_synthetic_seed");require("S2_ONLY_SCHEMA15_TO_17_V404".equals(seed.path("contract").asText()),"Explicit new404 seed contract required");
            Path lineage=Paths.get(seed.path("path").asText());checkedFile(lineage,64*1024);require(DedicatedMysqlFixture.hash(lineage).equals(seed.path("sha256").asText()),"404 seed lineage seal changed");
            JsonNode prepared=JSON.readTree(Files.readAllBytes(lineage));
            require("PASS".equals(prepared.path("result").asText())&&"S2_ONLY_SCHEMA15_TO_17_V404".equals(prepared.path("contract").asText())
                &&"LOCAL / NON-PRODUCTION / DISPOSABLE TEST ENVIRONMENT".equals(prepared.path("environment").asText())
                &&prepared.path("inheritedMetadataRows").asInt()==15&&prepared.path("newTenantRows").asInt()==2&&prepared.path("businessRowsCopied").asInt()==0
                &&prepared.path("all15MetadataUnchanged").asBoolean()&&prepared.path("oldBusinessDatabasesUntouched").asBoolean(),"Actual local404 schema15-to17 lineage required");
            require(frozen.path("tables").path("tenant_schema_version").path("rows").asInt()==15&&frozen.path("tables").path("tenant").path("rows").asInt()==2,"Only metadata and newly owned tenants may be populated initially");
        }else {
        require(rows==31,"Local S2 synthetic full31 required; immutable full30 lineage is separately sealed");
        JsonNode seed=proof.path("local_s2_synthetic_seed");
        require("S2_ONLY_FULL30_TO_FULL31_V1".equals(seed.path("contract").asText())&&seed.path("immutableBaselineRows").asInt()==30&&seed.path("initialBaselineRows").asInt()==31&&proof.path("full_rows").asInt()==31,"Explicit synthetic31 lineage required; not an unchanged full30 claim");
        Path lineage=Paths.get(seed.path("path").asText());checkedFile(lineage,64*1024);require(DedicatedMysqlFixture.hash(lineage).equals(seed.path("sha256").asText()),"Synthetic seed lineage seal changed");
        JsonNode prepared=JSON.readTree(Files.readAllBytes(lineage));
        require("PASS".equals(prepared.path("result").asText())&&"S2_ONLY_FULL30_TO_FULL31_V1".equals(prepared.path("contract").asText())&&"LOCAL / NON-PRODUCTION / DISPOSABLE TEST ENVIRONMENT".equals(prepared.path("environment").asText())&&prepared.path("immutableBaselineRows").asInt()==30&&prepared.path("initialBaselineRows").asInt()==31&&prepared.path("otherInheritedRows29Retained").asBoolean()&&prepared.path("archivedFull30Untouched").asBoolean(),"Actual local-only synthetic seed proof required");
        }
        require(db.queryForList("SELECT id FROM tenant WHERE status='ACTIVE' AND config_ready=1 ORDER BY id",Long.class).equals(Arrays.asList(1L,2L)),"Exact two ready synthetic S2 tenants required");
    }
    private static JsonNode readEvidence(Map<String,String> settings,String key,int maximum) throws Exception {Path p=Paths.get(settings.get(key)).toAbsolutePath().normalize();checkedFile(p,maximum);require(DedicatedMysqlFixture.hash(p).equals(settings.get(key+"Sha256")),"Private evidence hash changed: "+key);return JSON.readTree(Files.readAllBytes(p));}
    private static void checkedFile(Path p,int maximum)throws Exception {require(!Files.isSymbolicLink(p)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)>0&&Files.size(p)<=maximum,"Private fixture file invalid");}
    private static JsonNode single(String value)throws Exception {JsonNode rows=JSON.readTree(value);require(rows.isArray()&&rows.size()==1,"Unique actual fixture identity required");return rows.get(0);}
    private static String command(String... args)throws Exception {
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();ExecutorService reader=Executors.newSingleThreadExecutor();
        Future<byte[]> bytes=reader.submit(()->{ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=process.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IllegalArgumentException("Fixture inspect output exceeded budget");out.write(b,0,n);}}return out.toByteArray();});
        try {byte[] output=bytes.get(30,TimeUnit.SECONDS);require(process.waitFor(5,TimeUnit.SECONDS)&&process.exitValue()==0,"Fixture inspect failed");return new String(output,StandardCharsets.UTF_8);}
        finally {if(process.isAlive())process.destroyForcibly();bytes.cancel(true);reader.shutdownNow();}
    }
    private static String digest(byte[] bytes)throws Exception {StringBuilder hex=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}
