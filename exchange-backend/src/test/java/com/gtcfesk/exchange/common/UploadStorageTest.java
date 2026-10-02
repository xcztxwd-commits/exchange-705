package com.gtcfesk.exchange.common;

import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.user.FileUploadService;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real temporary filesystem writes/reads. Authorization collaborators remain separate unit dependencies. */
class UploadStorageTest {
    @TempDir Path temporary;
    private UploadStorage storage;
    private FileUploadController multipart;
    private FileUploadService signatures;
    private ImageController reader;
    private long tenant;
    @BeforeEach void setup(){
        tenant=System.currentTimeMillis()*1000L+new Random().nextInt(500);
        TenantContext.open(tenant);actor("77");
        storage=new UploadStorage(temporary.resolve("configured uploads").toString());
        multipart=new FileUploadController();signatures=new FileUploadService();reader=new ImageController();
        for(Object component:Arrays.asList(multipart,signatures,reader))ReflectionTestUtils.setField(component,"storage",storage);
        ReflectionTestUtils.setField(reader,"published",mock(PublishedTenantFiles.class));
        ReflectionTestUtils.setField(reader,"access",mock(BackendAccess.class));
        ReflectionTestUtils.setField(reader,"audit",mock(ControlAuditService.class));
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();TenantContext.clear();}
    private void actor(String user){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))));}
    private static byte[] png() throws IOException {
        BufferedImage image=new BufferedImage(2,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xff112233);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",bytes));return bytes.toByteArray();
    }
    private MockMultipartFile image() throws IOException{return new MockMultipartFile("file","untrusted-name.jpg","image/png",png());}
    private String multipartImage() throws IOException {
        ResponseEntity<?> response=multipart.uploadImage(SecurityContextHolder.getContext().getAuthentication(),image());
        assertEquals(200,response.getStatusCodeValue());return (String)((Map<?,?>)response.getBody()).get("url");
    }
    private Path stored(String url){
        String prefix="/api/uploads/";assertTrue(url.startsWith(prefix));
        Path path=temporary.resolve("configured uploads").resolve(url.substring(prefix.length())).normalize();
        assertTrue(path.startsWith(temporary.resolve("configured uploads")));return path;
    }
    private ResponseEntity<Resource> get(String url){return reader.getFile(new MockHttpServletRequest("GET",url));}
    private byte[] delivered(String url) throws IOException {
        ResponseEntity<Resource> response=get(url);assertEquals(200,response.getStatusCodeValue());
        assertEquals("private, no-store",response.getHeaders().getFirst("Cache-Control"));
        assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));
        try(InputStream stream=Objects.requireNonNull(response.getBody()).getInputStream()){return StreamUtils.copyToByteArray(stream);}
    }
    private void assertNoDefaultOwnerFiles(){
        Path root=Paths.get("uploads").toAbsolutePath().normalize();
        assertFalse(Files.exists(root.resolve("images").resolve(tenant+"/user/77")));
        assertFalse(Files.exists(root.resolve("audio").resolve(tenant+"/user/77")));
    }

    @Test void springPropertySelectsExactRootWithoutStaticDirectoryCreation(){
        Path configured=temporary.resolve("spring supplied").toAbsolutePath().normalize();
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()){
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Collections.singletonMap("file.upload-dir",configured.toString())));
            context.register(UploadStorage.class);context.refresh();UploadStorage resolved=context.getBean(UploadStorage.class);
            assertEquals(configured.resolve("images"),resolved.images());assertEquals(configured.resolve("audio"),resolved.audio());
            assertFalse(Files.exists(configured));
        }
        assertNoDefaultOwnerFiles();
    }
    @Test void multipartAndSignatureEntrancesUseSameConfiguredRootAndRealAuthorizedRead() throws Exception {
        assertNoDefaultOwnerFiles();
        for(String url:Arrays.asList(multipartImage(),signatures.uploadImage(image()),signatures.uploadBase64Image("data:image/png;base64,"+Base64.getEncoder().encodeToString(png())))){
            Path file=stored(url);assertTrue(Files.isRegularFile(file));
            assertTrue(file.startsWith(storage.images().resolve(tenant+"/user/77")));
            assertTrue(file.getFileName().toString().endsWith(".png"));
            assertArrayEquals(Files.readAllBytes(file),delivered(url));
            BufferedImage decoded=ImageIO.read(file.toFile());assertNotNull(decoded);assertEquals(2,decoded.getWidth());assertEquals(0xff112233,decoded.getRGB(0,0));
        }
        assertNoDefaultOwnerFiles();
    }
    @Test void audioUsesConfiguredAudioDirectoryAndSameAuthorizedReader() throws Exception {
        byte[] wav="RIFF0000WAVEpayload".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ResponseEntity<?> response=multipart.uploadAudio(SecurityContextHolder.getContext().getAuthentication(),new MockMultipartFile("file","sound.wav","audio/wav",wav));
        assertEquals(200,response.getStatusCodeValue());String url=(String)((Map<?,?>)response.getBody()).get("url");
        assertTrue(stored(url).startsWith(storage.audio().resolve(tenant+"/user/77")));
        assertArrayEquals(wav,Files.readAllBytes(stored(url)));assertArrayEquals(wav,delivered(url));assertNoDefaultOwnerFiles();
    }
    @Test void privateReadStillRejectsAnotherUserAnotherTenantAndMissingContext() throws Exception {
        String url=multipartImage();assertEquals(200,get(url).getStatusCodeValue());
        actor("78");assertEquals(404,get(url).getStatusCodeValue());
        actor("77");TenantContext.clear();TenantContext.open(tenant+1);assertEquals(404,get(url).getStatusCodeValue());
        TenantContext.clear();assertEquals(401,get(url).getStatusCodeValue());
    }
    @Test void imageValidationIsNotWeakenedByConfiguredStorage() throws Exception {
        MockMultipartFile bad=new MockMultipartFile("file","pretend.png","image/png","not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(BusinessException.class,()->multipart.uploadImage(SecurityContextHolder.getContext().getAuthentication(),bad));
        assertThrows(BusinessException.class,()->signatures.uploadImage(bad));
        try(java.util.stream.Stream<Path> files=Files.walk(storage.images())){assertEquals(0,files.filter(Files::isRegularFile).count());}
        assertNoDefaultOwnerFiles();
    }
    @Test void unavailableConfiguredRootNeverFallsBackToDefaultUploads() throws Exception {
        byte[] evidence={1,2,3};Path blocked=temporary.resolve("unavailable");UploadStorage unavailable=new UploadStorage(blocked.toString());Files.write(blocked,evidence);
        ReflectionTestUtils.setField(multipart,"storage",unavailable);ReflectionTestUtils.setField(signatures,"storage",unavailable);
        ResponseEntity<?> response=multipart.uploadImage(SecurityContextHolder.getContext().getAuthentication(),image());
        assertEquals(400,response.getStatusCodeValue());assertEquals(false,((Map<?,?>)response.getBody()).get("success"));
        assertThrows(BusinessException.class,()->signatures.uploadImage(image()));
        assertArrayEquals(evidence,Files.readAllBytes(blocked));assertNoDefaultOwnerFiles();
    }
    @Test void emptyOrExistingNonDirectoryConfigurationFailsClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new UploadStorage(""));assertThrows(IllegalArgumentException.class,()->new UploadStorage("   "));
        Path file=temporary.resolve("not a directory");Files.write(file,new byte[]{1});
        assertThrows(IllegalArgumentException.class,()->new UploadStorage(file.toString()));assertNoDefaultOwnerFiles();
    }
    @Test void ordinaryDefaultRemainsRelativeUploadsWithoutCreatingIt(){
        Path root=Paths.get("uploads").toAbsolutePath().normalize();boolean images=Files.exists(root.resolve("images")),audio=Files.exists(root.resolve("audio"));
        UploadStorage defaultStorage=new UploadStorage("uploads");assertEquals(root.resolve("images"),defaultStorage.images());assertEquals(root.resolve("audio"),defaultStorage.audio());
        assertEquals(images,Files.exists(root.resolve("images")));assertEquals(audio,Files.exists(root.resolve("audio")));assertNoDefaultOwnerFiles();
    }
}
