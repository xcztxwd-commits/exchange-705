package com.gtcfesk.exchange.common;
import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ImageAuthorizationTest {
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
 private UploadStorage storage;
 @BeforeEach void setup(){TenantContext.open(1L);storage=new UploadStorage(temporary.resolve("configured").toString());}
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();TenantContext.clear();}
 void actor(String name,String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,Collections.singleton(new SimpleGrantedAuthority(role))));}
 ImageController controller(PublishedTenantFiles published,BackendAccess access){ImageController c=new ImageController();ReflectionTestUtils.setField(c,"storage",storage);ReflectionTestUtils.setField(c,"published",published);ReflectionTestUtils.setField(c,"access",access);ReflectionTestUtils.setField(c,"audit",mock(com.gtcfesk.exchange.control.ControlAuditService.class));return c;}
 @Test void controlFileDeliveryRequiresSuccessfulAudit()throws Exception{
  actor("-9","ROLE_SUPER_ADMIN");((UsernamePasswordAuthenticationToken)SecurityContextHolder.getContext().getAuthentication()).setDetails(new com.gtcfesk.exchange.control.ControlIdentity(9L,1L,"test-access"));
  PublishedTenantFiles p=mock(PublishedTenantFiles.class);BackendAccess a=mock(BackendAccess.class);ImageController c=controller(p,a);
  com.gtcfesk.exchange.control.ControlAuditService audit=mock(com.gtcfesk.exchange.control.ControlAuditService.class);ReflectionTestUtils.setField(c,"audit",audit);
  java.nio.file.Path base=storage.images();java.nio.file.Path dir=base.resolve("1/staff/-9");java.nio.file.Files.createDirectories(dir);
  String name="audit-test-"+UUID.randomUUID()+".png",file="1/staff/-9/"+name;java.nio.file.Path path=dir.resolve(name);
  try{
   java.nio.file.Files.write(path,new byte[]{(byte)137,80,78,71,13,10,26,10});MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/uploads/images/"+file);
   assertEquals(200,c.getFile(request).getStatusCodeValue());verify(audit).recordCurrent("file.access.granted",file,"image",null);
   doThrow(new IllegalStateException("test audit unavailable")).when(audit).recordCurrent(anyString(),anyString(),anyString(),isNull());assertEquals(404,c.getFile(request).getStatusCodeValue());
  }finally{java.nio.file.Files.deleteIfExists(path);}
 }
 @Test void sharedMaterialRequiresAdminModuleAndLibraryReference()throws Exception{
  actor("10","ROLE_ADMIN");PublishedTenantFiles p=mock(PublishedTenantFiles.class);BackendAccess a=mock(BackendAccess.class);ImageController c=controller(p,a);
  java.nio.file.Path base=storage.images();java.nio.file.Path dir=base.resolve("1/staff/9");java.nio.file.Files.createDirectories(dir);
  String file="1/staff/9/library-"+UUID.randomUUID()+".gif";java.nio.file.Path path=base.resolve(file);
  try{java.nio.file.Files.write(path,new byte[]{71,73,70,56,57,97});MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/uploads/images/"+file);
   assertEquals(404,c.getFile(request).getStatusCodeValue());when(a.canReadMenu("announcement")).thenReturn(true);assertEquals(404,c.getFile(request).getStatusCodeValue());when(p.allowsMaterial(file)).thenReturn(true);assertEquals(200,c.getFile(request).getStatusCodeValue());
   actor("10","ROLE_USER");assertEquals(404,c.getFile(request).getStatusCodeValue());
  }finally{java.nio.file.Files.deleteIfExists(path);}
 }
 @Test void staffNamespacesKeepAgentAdminAndControlDistinct(){
  actor("9","ROLE_ADMIN");assertEquals("1/staff/9",TenantFiles.ownerPath());
  actor("agent-9","ROLE_AGENT");assertEquals("1/staff/agent-9",TenantFiles.ownerPath());
  actor("-9","ROLE_SUPER_ADMIN");assertEquals("1/staff/-9",TenantFiles.ownerPath());
  actor("9","ROLE_USER");assertEquals("1/user/9",TenantFiles.ownerPath());
 }
 @Test void shareLibraryReadersNeedBothMenuAndReferenceWhileConsumersNeedPublication()throws Exception{
  actor("10","ROLE_ADMIN");PublishedTenantFiles p=mock(PublishedTenantFiles.class);BackendAccess a=mock(BackendAccess.class);ImageController c=controller(p,a);
  java.nio.file.Path base=storage.images(),dir=base.resolve("1/staff/9");java.nio.file.Files.createDirectories(dir);
  String file="1/staff/9/share-"+UUID.randomUUID()+".png";java.nio.file.Path path=base.resolve(file);
  try{java.nio.file.Files.write(path,new byte[]{(byte)137,80,78,71,13,10,26,10});MockHttpServletRequest req=new MockHttpServletRequest("GET","/api/uploads/images/"+file);
   when(p.allowsShare(file,false)).thenReturn(true);assertEquals(404,c.getFile(req).getStatusCodeValue());when(a.canReadMenu("share_templates")).thenReturn(true);assertEquals(200,c.getFile(req).getStatusCodeValue());
   actor("10","ROLE_USER");assertEquals(404,c.getFile(req).getStatusCodeValue());when(p.allows(file,false,true)).thenReturn(true);assertEquals(200,c.getFile(req).getStatusCodeValue());
   assertEquals(404,c.getFile(new MockHttpServletRequest("GET","/api/uploads/images/2/staff/9/test.png")).getStatusCodeValue());
  }finally{java.nio.file.Files.deleteIfExists(path);}
 }
 @Test void usersPermissionDoesNotExposeAnotherStaffUnpublishedFile(){
  actor("agent-9","ROLE_AGENT");PublishedTenantFiles p=mock(PublishedTenantFiles.class);BackendAccess a=mock(BackendAccess.class);
  assertEquals(404,controller(p,a).getFile(new MockHttpServletRequest("GET","/api/uploads/images/1/staff/9/private.png")).getStatusCodeValue());verifyNoInteractions(a);
 }
 @Test void usersPermissionDoesNotReplaceMaterialModuleAndReferenceCheck(){
  actor("9","ROLE_ADMIN");PublishedTenantFiles p=mock(PublishedTenantFiles.class);BackendAccess a=mock(BackendAccess.class);when(a.canReadMenu("users")).thenReturn(true);
  assertEquals(404,controller(p,a).getFile(new MockHttpServletRequest("GET","/api/uploads/images/1/user/12/private.png")).getStatusCodeValue());
  verify(a).checkUser(12L);verify(p).allowsReview("1/user/12/private.png",12L,Collections.emptySet());
 }
 @Test void userAvatarsRequireUsersModuleExactProfileReferenceAndOwnerScope()throws Exception{
  actor("10","ROLE_ADMIN");PublishedTenantFiles p=mock(PublishedTenantFiles.class);BackendAccess a=mock(BackendAccess.class);ImageController c=controller(p,a);
  java.nio.file.Path dir=storage.images().resolve("1/user/12");java.nio.file.Files.createDirectories(dir);
  String file="1/user/12/avatar.png";java.nio.file.Files.write(storage.images().resolve(file),new byte[]{(byte)137,80,78,71,13,10,26,10});
  MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/uploads/images/"+file);
  when(p.allowsUserAvatar(file,12L)).thenReturn(true);assertEquals(404,c.getFile(request).getStatusCodeValue());
  when(a.canReadMenu("users")).thenReturn(true);assertEquals(200,c.getFile(request).getStatusCodeValue());verify(a,atLeastOnce()).checkUser(12L);
  when(p.allowsUserAvatar(file,12L)).thenReturn(false);assertEquals(404,c.getFile(request).getStatusCodeValue());
  when(p.allowsUserAvatar(file,12L)).thenReturn(true);doThrow(new org.springframework.security.access.AccessDeniedException("not subordinate")).when(a).checkUser(12L);
  assertThrows(org.springframework.security.access.AccessDeniedException.class,()->c.getFile(request));
  actor("13","ROLE_USER");assertEquals(404,c.getFile(request).getStatusCodeValue());
  SecurityContextHolder.clearContext();assertEquals(401,c.getFile(request).getStatusCodeValue());
  assertEquals(404,c.getFile(new MockHttpServletRequest("GET","/api/uploads/images/2/user/12/avatar.png")).getStatusCodeValue());
 }
}
