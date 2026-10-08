package com.gtcfesk.exchange.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.tenant.BootTenantFixture;
import io.minio.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP, JWT/tenant/menu guards, persisted KYC/avatar references and private MinIO. */
@EnabledIfEnvironmentVariable(named="VIDEO_TEST_MINIO_ENDPOINT", matches="http://127\\.0\\.0\\.1:[0-9]+")
@SpringBootTest(classes=AdminPermissionIntegrationTest.PermissionApp.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"server.address=127.0.0.1", "spring.datasource.url=jdbc:h2:mem:media_acceptance;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false", "logging.level.root=ERROR", "jwt.secret=cGVybWlzc2lvbi10ZXN0LW9ubHktbm90LWEtcmVhbC1zZWNyZXQ="})
class MediaMinioAcceptanceTest extends AdminPermissionIntegrationTest {
    static final String PREFIX="local-acceptance-"+UUID.randomUUID();
    static final String BUCKET=System.getenv("VIDEO_TEST_MINIO_BUCKET")==null?"exchange-media":System.getenv("VIDEO_TEST_MINIO_BUCKET");
    static final Path UNUSED=Paths.get(System.getProperty("java.io.tmpdir"), PREFIX);
    @LocalServerPort int port;
    final OkHttpClient http=new OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build();
    @DynamicPropertySource static void storage(DynamicPropertyRegistry properties) {
        properties.add("file.storage",()->"minio"); properties.add("file.upload-dir",()->UNUSED.toString());
        properties.add("minio.endpoint",()->System.getenv("VIDEO_TEST_MINIO_ENDPOINT"));
        properties.add("minio.access-key",()->System.getenv("VIDEO_TEST_MINIO_ACCESS_KEY"));
        properties.add("minio.secret-key",()->System.getenv("VIDEO_TEST_MINIO_SECRET_KEY"));
        properties.add("minio.bucket",()->BUCKET); properties.add("minio.prefix",()->PREFIX);
    }
    MinioClient objects() {return MinioClient.builder().endpoint(System.getenv("VIDEO_TEST_MINIO_ENDPOINT")).credentials(System.getenv("VIDEO_TEST_MINIO_ACCESS_KEY"),System.getenv("VIDEO_TEST_MINIO_SECRET_KEY")).build();}
    byte[] png(int color)throws Exception {java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(3,2,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,color);ByteArrayOutputStream output=new ByteArrayOutputStream();assertTrue(javax.imageio.ImageIO.write(image,"png",output));return output.toByteArray();}
    UserAccount user() {UserAccount user=new UserAccount();user.setEmail("media_"+UUID.randomUUID()+"@example.invalid");user.setPasswordHash("test-hash");user.setCurrentToken(UUID.randomUUID().toString());return users.saveAndFlush(user);}
    String userToken(UserAccount user) {Map<String,Object> claims=new HashMap<>();claims.put("userType","user");claims.put("sid",user.getCurrentToken());claims.put("credential",jwt.credentialKey(user.getPasswordHash()));return jwt.generateToken("user-"+user.getId(),claims);}
    static class Reply {int status;byte[] bytes;String cache;Reply(Response response)throws IOException{status=response.code();bytes=response.body().bytes();cache=response.header("Cache-Control");}}
    Reply api(String host,String path,String method,RequestBody body,String token)throws Exception {
        Request.Builder request=new Request.Builder().url("http://127.0.0.1:"+port+path).header("Host",host).header("Origin","https://"+host).method(method,body);
        if(token!=null)request.header("Authorization","Bearer "+token);
        try(Response response=http.newCall(request.build()).execute()){return new Reply(response);}
    }
    JsonNode data(Reply response) throws Exception {assertEquals(200,response.status,new String(response.bytes,"UTF-8"));return json.readTree(response.bytes);}
    MultipartBody.Builder form(){return new MultipartBody.Builder().setType(MultipartBody.FORM);}
    RequestBody image(byte[] bytes){return RequestBody.create(MediaType.parse("image/png"),bytes);}
    void readable(String url,String token,boolean admin,byte[] expected)throws Exception {
        Reply response=api(admin?BootTenantFixture.ADMIN:BootTenantFixture.FRONT,url,"GET",null,token);
        assertEquals(200,response.status,url);assertArrayEquals(expected,response.bytes);assertEquals("private, no-store",response.cache);
        String key=PREFIX+"/"+url.substring("/api/uploads/".length());
        try(InputStream stream=objects().getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).build())) {assertArrayEquals(expected,org.springframework.util.StreamUtils.copyToByteArray(stream));}
        Request direct=new Request.Builder().url(System.getenv("VIDEO_TEST_MINIO_ENDPOINT")+"/"+BUCKET+"/"+key).build();
        try(Response directResponse=http.newCall(direct).execute()){assertEquals(403,directResponse.code());}
    }
    @AfterEach void removeOwnTestObjects()throws Exception {
        MinioClient client=objects();
        if (System.getenv("VIDEO_TEST_MINIO_ROOT_ACCESS_KEY") != null) client=MinioClient.builder().endpoint(System.getenv("VIDEO_TEST_MINIO_ENDPOINT"))
                .credentials(System.getenv("VIDEO_TEST_MINIO_ROOT_ACCESS_KEY"),System.getenv("VIDEO_TEST_MINIO_ROOT_SECRET_KEY")).build();
        for(io.minio.Result<io.minio.messages.Item> object:client.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(PREFIX+"/").recursive(true).build()))client.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(object.get().objectName()).build());
        assertFalse(Files.exists(UNUSED.resolve("images")));assertFalse(Files.exists(UNUSED.resolve("audio")));assertFalse(Files.exists(UNUSED.resolve("thumbnails")));
    }
    @Test void identityUploadsReadForUserAndCorrectBackendPermissions()throws Exception {
        UserAccount owner=user(),other=user();String ownerToken=userToken(owner),otherToken=userToken(other);
        byte[] avatar=png(0xff225588),front=png(0xff338855),back=png(0xff885533);
        String avatarUrl=data(api(BootTenantFixture.FRONT,"/api/user/profile","PUT",form().addFormDataPart("nickname","MinIO QA").addFormDataPart("avatar","avatar.png",image(avatar)).build(),ownerToken)).path("avatarUrl").asText();
        assertEquals(avatarUrl,users.findByTenantIdAndId(1L,owner.getId()).get().getAvatarUrl());
        data(api(BootTenantFixture.FRONT,"/api/kyc/submit","POST",form().addFormDataPart("realName","Synthetic Media QA").addFormDataPart("idNumber","QA-"+UUID.randomUUID()).addFormDataPart("idFrontImage","front.png",image(front)).addFormDataPart("idBackImage","back.png",image(back)).build(),ownerToken));
        JsonNode record=data(api(BootTenantFixture.FRONT,"/api/kyc/status","GET",null,ownerToken)).path("latestRecord");
        String frontUrl=record.path("idFrontImage").asText(),backUrl=record.path("idBackImage").asText();
        for(String url:Arrays.asList(avatarUrl,frontUrl,backUrl)) {
            readable(url,ownerToken,false,url.equals(avatarUrl)?avatar:url.equals(frontUrl)?front:back);
            String thumbnail=url.replace("/images/","/thumbnails/");
            byte[] expected=thumbnail(url.equals(avatarUrl)?avatar:url.equals(frontUrl)?front:back);
            readable(thumbnail,ownerToken,false,expected);
            assertEquals(404,api(BootTenantFixture.FRONT,thumbnail,"GET",null,otherToken).status);
            assertTrue(api(BootTenantFixture.FRONT,thumbnail,"GET",null,null).status>=400);
            assertTrue(api("b.mt705.test",thumbnail,"GET",null,ownerToken).status>=400);
            assertEquals(404,api(BootTenantFixture.ADMIN,thumbnail,"GET",null,token).status);
            assertEquals(404,api(BootTenantFixture.FRONT,url,"GET",null,otherToken).status);
            assertTrue(api(BootTenantFixture.FRONT,url,"GET",null,null).status>=400);
            assertTrue(api("b.mt705.test",url,"GET",null,ownerToken).status>=400);
            assertEquals(404,api(BootTenantFixture.ADMIN,url,"GET",null,token).status);
        }
        grant("users"); grant("users:detail");
        JsonNode userDetails=data(api(BootTenantFixture.ADMIN,"/api/admin/users/"+owner.getId(),"GET",null,token));
        assertTrue(userDetails.toString().contains(avatarUrl));readable(avatarUrl,token,true,avatar);
        assertEquals(404,api(BootTenantFixture.ADMIN,frontUrl,"GET",null,token).status);
        grant("kyc_review");
        JsonNode review=data(api(BootTenantFixture.ADMIN,"/api/admin/kyc/list?userId="+owner.getId(),"GET",null,token));
        assertTrue(review.toString().contains(frontUrl));assertTrue(review.toString().contains(backUrl));
        readable(frontUrl,token,true,front);readable(backUrl,token,true,back);
        readable(avatarUrl.replace("/images/","/thumbnails/"),token,true,thumbnail(avatar));
        readable(frontUrl.replace("/images/","/thumbnails/"),token,true,thumbnail(front));
        readable(backUrl.replace("/images/","/thumbnails/"),token,true,thumbnail(back));
        System.out.println("LOCAL_MINIO_IDENTITY_ACCEPTANCE_PASS realHttp=true avatar=true kycFrontAndBack=true persistedReferences=true userRead=true backendRead=true permissionDenial=true crossTenantDenial=true anonymousMinio=403 localFallback=false");
    }
    byte[] thumbnail(byte[] image)throws Exception {return com.gtcfesk.exchange.common.ImageFiles.thumbnail(new ByteArrayInputStream(image));}
}
