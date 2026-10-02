package com.gtcfesk.exchange.tenant;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
/** Fixture transport only. Native port requires this run's identified 5.7 server and actual independent populated restore proof. */
public final class DedicatedMysqlFixture {
 private DedicatedMysqlFixture(){}
 public static DriverManagerDataSource fromProperty(String key)throws Exception{
  String value=System.getProperty(key);
  if(value==null||value.trim().isEmpty())throw new IllegalStateException("Explicit identified/restored MySQL fixture required: -D"+key+"=PATH");
  Path path=Paths.get(value).toAbsolutePath().normalize();
  if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>64*1024)throw new IllegalArgumentException("Fixture connection file invalid");
  return open(new ObjectMapper().readValue(Files.readAllBytes(path),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}));
 }
 public static DriverManagerDataSource open(Map<String,String> settings)throws Exception{
  String url=settings.get("url");
  if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:(64029|33318|33418)/mt705_[a-zA-Z0-9_]+\\?.*"))throw new IllegalArgumentException("Only dedicated loopback mt705 fixture databases permitted");
  boolean dockerTarget="docker".equals(settings.get("fixtureTransport"));
  if(dockerTarget&&!url.startsWith("jdbc:mysql://127.0.0.1:33318/"))throw new IllegalArgumentException("Stage-one Docker transport requires explicit loopback 33318");
  boolean nativeTarget=!dockerTarget&&!url.startsWith("jdbc:mysql://127.0.0.1:64029/");
  JsonNode proof=null;
  if(nativeTarget||dockerTarget){
   Path path=Paths.get(Objects.requireNonNull(settings.get("fixtureProof"),"Native fixture restore proof required")).toAbsolutePath().normalize();
   if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>192*1024||!hash(path).equals(settings.get("fixtureProofSha256")))throw new IllegalArgumentException("Fixture proof file/hash invalid");
   proof=new ObjectMapper().readTree(Files.readAllBytes(path));
   if(!"PASS".equals(proof.path("result").asText())||!proof.path("source").path("test_instance").asBoolean()||!proof.path("original_records_unchanged").asBoolean()||!"PASS".equals(proof.path("independent_restore_before_ddl").path("result").asText())||!proof.path("independent_restore_before_ddl").path("fingerprint_equal").asBoolean()||proof.path("source").path("server_uuid").asText().isEmpty()||proof.path("source").path("server_uuid").asText().equals(proof.path("independent_restore_before_ddl").path("target").path("server_uuid").asText()))throw new IllegalArgumentException("Actual independent fixture restore not proven");
   Path backup=Paths.get(proof.path("backup").path("path").asText());if(!hash(backup).equals(proof.path("backup").path("sha256").asText()))throw new IllegalArgumentException("Fixture backup no longer matches proof");
  }
  if(dockerTarget)verifyDocker(settings,proof);
  DriverManagerDataSource source=new DriverManagerDataSource(url,settings.get("username"),settings.get("password"));
  try(Connection c=source.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT @@server_uuid,@@datadir,@@port,VERSION(),DATABASE()")){
   if(!r.next()||!r.getString(4).startsWith("5.7.")||!r.getString(5).matches("mt705_[a-zA-Z0-9_]+"))throw new IllegalArgumentException("Disposable MySQL5.7 database required");
   if(dockerTarget&&(!r.getString(5).equals(proof.path("source").path("database").asText())||!r.getString(1).equals(settings.get("serverUuid"))||!r.getString(1).equals(proof.path("source").path("server_uuid").asText())||r.getInt(3)!=3306||!r.getString(2).equals(proof.path("source").path("datadir").asText())))throw new IllegalArgumentException("Docker fixture server identity changed; no writes permitted");
   if(nativeTarget&&(!r.getString(5).equals(proof.path("source").path("database").asText())||!r.getString(1).equals(proof.path("source").path("server_uuid").asText())||r.getInt(3)!=proof.path("source").path("port").asInt()||!Paths.get(r.getString(2)).toRealPath().equals(Paths.get(proof.path("source").path("datadir").asText()).toRealPath())))throw new IllegalArgumentException("Native fixture server identity changed; no writes permitted");
  }
  return source;
 }
 static void verifyDocker(Map<String,String> settings,JsonNode proof)throws Exception{
  String run=settings.get("runId"),name=settings.get("containerName"),id=settings.get("containerId");
  if(run==null||!run.matches("stage1-[0-9]{8}-[0-9]{6}")||name==null||!name.equals("mt705-"+run+"-mysql")||id==null||!id.matches("[a-f0-9]{64}")||settings.get("serverUuid")==null)throw new IllegalArgumentException("Explicit stage-one Docker identity required");
  JsonNode source=proof.path("source"),restore=proof.path("independent_restore_before_ddl").path("target");
  if(!run.equals(source.path("run_id").asText())||!name.equals(source.path("container").asText())||!id.equals(source.path("container_id").asText())||!settings.get("serverUuid").equals(source.path("server_uuid").asText())||source.path("port").asInt()!=3306)throw new IllegalArgumentException("Docker source proof identity mismatch");
  JsonNode inspected=inspect(name,run,id);JsonNode ports=inspected.path("NetworkSettings").path("Ports");
  JsonNode binding=ports.path("3306/tcp");
  if(!binding.isArray()||binding.size()!=1||!"127.0.0.1".equals(binding.get(0).path("HostIp").asText())||!"33318".equals(binding.get(0).path("HostPort").asText()))throw new IllegalArgumentException("Docker fixture is not bound to exact dedicated loopback port");
  for(Iterator<Map.Entry<String,JsonNode>> i=ports.fields();i.hasNext();){Map.Entry<String,JsonNode> port=i.next();if(!"3306/tcp".equals(port.getKey())&&!port.getValue().isNull())throw new IllegalArgumentException("Unexpected Docker fixture published port");}
  String restoreName=restore.path("container").asText(),restoreId=restore.path("container_id").asText();
  if(!run.equals(restore.path("run_id").asText())||id.equals(restoreId)||!restoreId.matches("[a-f0-9]{64}")||!restoreName.startsWith("mt705-"+run+"-")||!restore.path("database").asText().matches("mt705_[a-zA-Z0-9_]+"))throw new IllegalArgumentException("Restore is not an independent same-run Docker instance");
  JsonNode restored=inspect(restoreName,run,restoreId);
  for(Iterator<JsonNode> i=restored.path("NetworkSettings").path("Ports").elements();i.hasNext();)if(!i.next().isNull())throw new IllegalArgumentException("Independent restore fixture must not publish ports");
  String liveUuid=command("docker","exec",restoreName,"sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql -uroot --batch --skip-column-names -e 'SELECT @@server_uuid'").trim();
  if(!liveUuid.equals(restore.path("server_uuid").asText())||liveUuid.equals(settings.get("serverUuid")))throw new IllegalArgumentException("Independent Docker restore server UUID changed");
 }
 static JsonNode inspect(String name,String run,String id)throws Exception{
  JsonNode rows=new ObjectMapper().readTree(command("docker","inspect","--type","container",name));
  if(!rows.isArray()||rows.size()!=1)throw new IllegalArgumentException("Docker fixture inspection failed");
  JsonNode row=rows.get(0),labels=row.path("Config").path("Labels");
  if(!id.equals(row.path("Id").asText())||!("/"+name).equals(row.path("Name").asText())||!row.path("State").path("Running").asBoolean()||!"true".equals(labels.path("com.gtcfesk.multitenant.test").asText())||!run.equals(labels.path("com.gtcfesk.stage1.run").asText())||!row.path("Config").path("Image").asText().matches(".*mysql:5\\.7.*"))throw new IllegalArgumentException("Live Docker fixture ownership/identity mismatch");
  return row;
 }
 static String command(String... args)throws Exception{
  Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
  java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];int n;
  try(java.io.InputStream in=process.getInputStream()){while((n=in.read(b))!=-1){if(output.size()+n>1024*1024){process.destroyForcibly();throw new IllegalArgumentException("Fixture command output invalid");}output.write(b,0,n);}}
  if(process.waitFor()!=0)throw new IllegalArgumentException("Fixture identity command failed");
  return new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
 }
 public static String hash(Path path)throws Exception{if(Files.isSymbolicLink(path))throw new IllegalArgumentException("Fixture evidence symlink refused");java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(java.io.InputStream in=Files.newInputStream(path)){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)digest.update(b,0,n);}StringBuilder value=new StringBuilder();for(byte b:digest.digest())value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
}
