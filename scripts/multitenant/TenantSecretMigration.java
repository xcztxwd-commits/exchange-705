package com.gtcfesk.exchange.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Offline fixture-only CLI: reuse application crypto/AAD/classification, never print plaintext. */
public final class TenantSecretMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[][] TABLES = {{"system_config","config_key","config_value"},{"tenant_policy","policy_key","policy_value"}};
    private static String sha(Path file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(java.io.InputStream in=Files.newInputStream(file)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b&255));return hex.toString();
    }
    @SuppressWarnings("unchecked") private static void run(String[] args) throws Exception {
        if(args.length<2)throw new IllegalArgumentException("Connection and protected key JSON required; default is dry-run");
        Map<String,String> config=JSON.readValue(new File(args[0]),Map.class);
        String url=config.get("url"),database=config.get("database");
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:64029/mt705_[a-zA-Z0-9_]+\\?.*")
            ||!url.contains("/"+database+"?")||!"mt705-20260929-test-mysql".equals(config.get("container")))
            throw new IllegalArgumentException("Only dedicated localhost fixtures accepted");
        Map<String,String> keys=JSON.readValue(new File(args[1]),Map.class);
        String key=keys.get("dataKey");
        if(key==null||Base64.getDecoder().decode(key).length!=32)throw new IllegalArgumentException("Valid application dataKey required");
        TenantSecrets secrets=new TenantSecrets();java.lang.reflect.Field field=TenantSecrets.class.getDeclaredField("key");field.setAccessible(true);field.set(secrets,key);
        boolean apply=Arrays.asList(args).contains("--apply");int failAfter=-1;
        for(String arg:args)if(arg.startsWith("--fail-after="))failAfter=Integer.parseInt(arg.substring(13));
        if(apply){
            String proofArg=Arrays.stream(args).filter(a->a.startsWith("--proof=")).findFirst().orElseThrow(()->new IllegalArgumentException("Verified backup proof required"));
            Map<String,Object> proof=JSON.readValue(new File(proofArg.substring(8)),Map.class);
            if(!database.equals(proof.get("database"))||!Boolean.TRUE.equals(proof.get("restore_verified"))
                ||!sha(Paths.get((String)proof.get("backup_path"))).equals(proof.get("backup_sha256")))throw new IllegalArgumentException("Backup proof mismatch");
        }
        int changed=0,encrypted=0,empty=0;
        try(Connection c=DriverManager.getConnection(url,config.get("username"),config.get("password"))){
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(!apply);c.setAutoCommit(false);
            try {
                for(String[] table:TABLES){
                    String name=table[0],keyColumn=table[1],valueColumn=table[2];
                    List<Object[]> rows=new ArrayList<>();
                    try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT id,tenant_id,"+keyColumn+","+valueColumn+" FROM "+name+" ORDER BY tenant_id,id"+(apply?" FOR UPDATE":""))){
                        while(r.next())rows.add(new Object[]{r.getLong(1),r.getLong(2),r.getString(3),r.getString(4)});
                    }
                    boolean timestamp;
                    try(ResultSet columns=c.getMetaData().getColumns(c.getCatalog(),null,name,"updated_at")){timestamp=columns.next();}
                    for(Object[] row:rows){
                        String configKey=(String)row[2],value=(String)row[3];
                        if("tenant_policy".equals(name)){
                            if(!configKey.startsWith("config."))continue;
                            configKey=configKey.substring(7); // Same effective system-config key/AAD used by application.
                        }
                        if(!TenantSecrets.secret(configKey))continue;
                        if(value==null||value.isEmpty()){empty++;continue;}
                        if(TenantSecrets.MASK.equals(value))throw new IllegalStateException("Masked placeholder cannot be recovered");
                        try(TenantContext.Scope ignored=TenantContext.open((Long)row[1])){
                            if(value.startsWith("enc:v1:")){secrets.decrypt(configKey,value);encrypted++;continue;}
                            if(value.startsWith("enc:"))throw new IllegalStateException("Unknown ciphertext format");
                            String protectedValue=secrets.encrypt(configKey,value);
                            if(!value.equals(secrets.decrypt(configKey,protectedValue)))throw new IllegalStateException("Roundtrip mismatch");
                            if(apply){
                                String sql="UPDATE "+name+" SET "+valueColumn+"=?"+(timestamp?",updated_at=updated_at":"")+" WHERE tenant_id=? AND id=? AND BINARY "+valueColumn+"=BINARY ?";
                                try(PreparedStatement update=c.prepareStatement(sql)){
                                    update.setString(1,protectedValue);update.setLong(2,(Long)row[1]);update.setLong(3,(Long)row[0]);update.setString(4,value);
                                    if(update.executeUpdate()!=1)throw new IllegalStateException("Concurrent configuration change");
                                }
                                try(PreparedStatement verify=c.prepareStatement("SELECT "+valueColumn+" FROM "+name+" WHERE tenant_id=? AND id=?")){
                                    verify.setLong(1,(Long)row[1]);verify.setLong(2,(Long)row[0]);
                                    try(ResultSet r=verify.executeQuery()){if(!r.next()||!value.equals(secrets.decrypt(configKey,r.getString(1))))throw new IllegalStateException("Stored ciphertext mismatch");}
                                }
                            }
                            changed++;
                            if(apply&&changed==failAfter)throw new IllegalStateException("Injected fixture rollback");
                        }
                    }
                }
                if(apply)c.commit();else c.rollback();
            }catch(Exception failure){c.rollback();throw failure;}
        }
        System.out.println("TENANT_SECRET_MIGRATION "+(apply?"APPLIED":"DRY_RUN")+" plaintext_candidates="+changed+" validated_ciphertexts="+encrypted+" empty_preserved="+empty);
    }
    public static void main(String[] args){
        try{run(args);}catch(Exception failure){
            // Exception messages/SQL bindings can contain secrets: only print a constant failure boundary.
            System.err.println("TENANT_SECRET_MIGRATION_STOPPED transaction rolled back; protected evidence retained; no plaintext logged");System.exit(1);
        }finally{TenantContext.clear();}
    }
}
