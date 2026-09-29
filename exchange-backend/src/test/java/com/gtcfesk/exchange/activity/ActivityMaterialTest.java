package com.gtcfesk.exchange.activity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.AdminActivityMaterialController;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.config.AdminPermission;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ActivityMaterialTest {
 ActivityMaterialRepository repository=mock(ActivityMaterialRepository.class);
 AdminActivityMaterialController controller=new AdminActivityMaterialController(repository,new ObjectMapper());
 @BeforeEach void setup(){when(repository.save(any())).thenAnswer(i->i.getArgument(0));}
 AdminActivityMaterialController.Input input(String nodes){AdminActivityMaterialController.Input input=new AdminActivityMaterialController.Input();input.name="礼盒动画";input.nodesJson=nodes;return input;}
 @Test void storesSafeAnimatedTree(){ActivityMaterial saved=(ActivityMaterial)controller.save(input("[{\"type\":\"box\",\"motion\":\"float\",\"style\":{\"scale\":\"1.5\",\"z-index\":\"2\"},\"children\":[{\"type\":\"image\",\"src\":\"/api/uploads/images/test.gif\"}]}]"));assertTrue(saved.getNodesJson().contains("float"));assertEquals("礼盒动画",saved.getName());}
 @Test void rejectsUnsafeNodes(){for(String nodes:Arrays.asList("[]","[{\"type\":\"script\"}]","[{\"type\":\"box\",\"motion\":\"evil\"}]","[{\"type\":\"image\",\"src\":\"/api/uploads/images/2/staff/1/test.gif\"}]","[{\"type\":\"button\",\"action\":\"page\",\"target\":\"detail\"}]"))assertThrows(BusinessException.class,()->controller.save(input(nodes)));verify(repository,never()).save(any());}
 @Test void removalIsSoft(){ActivityMaterial material=new ActivityMaterial();when(repository.findById(7L)).thenReturn(Optional.of(material));controller.delete(7L);assertTrue(material.isDeleted());assertThrows(BusinessException.class,()->controller.delete(8L));verify(repository).findById(8L);verify(repository,never()).delete(any());}
 @Test void endpointsRequireAnnouncementPermission(){for(java.lang.reflect.Method method:AdminActivityMaterialController.class.getDeclaredMethods()){if(method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class)!=null||method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class)!=null||method.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class)!=null)assertEquals("announcement",method.getAnnotation(AdminPermission.class).menu());}}
}
