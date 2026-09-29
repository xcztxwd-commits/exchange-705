package com.gtcfesk.exchange.activity;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class ActivityDesignValidatorTest {
 final ObjectMapper mapper=new ObjectMapper();
 ObjectNode design(){ObjectNode root=mapper.createObjectNode();root.put("version",1);ArrayNode pages=root.putObject("locales").putObject("zh-CN").putArray("pages");for(String id:new String[]{"gift","detail","success"}){ObjectNode p=pages.addObject();p.put("id",id);p.put("name",id);p.putArray("nodes").addObject().put("type","text").put("text","Editable {amount}");}return root;}
 ObjectNode first(ObjectNode root){return (ObjectNode)root.path("locales").path("zh-CN").path("pages").get(0).path("nodes").get(0);}
 String validate(ObjectNode root){return ActivityDesignValidator.validate(root.toString(),"zh-CN",mapper);}
 @Test void actualEditorExportRoundTrips()throws Exception{try(java.io.InputStream stream=getClass().getResourceAsStream("/activity-design.json")){assertNotNull(stream);JsonNode tree=mapper.readTree(stream);assertEquals(tree,mapper.readTree(ActivityDesignValidator.validate(tree.toString(),"zh-CN",mapper)));}}
 @Test void validTreePreservesEditableTextAndAssets()throws Exception{ObjectNode d=design();first(d).put("type","image").put("src","/api/uploads/images/animation.gif");assertTrue(validate(d).contains("animation.gif"));assertNull(ActivityDesignValidator.validate(null,"zh-CN",mapper));}
 @Test void rejectsScriptComponentsAndUnsupportedAssetPaths(){ObjectNode d=design();first(d).put("type","script");assertThrows(BusinessException.class,()->validate(d));first(d).put("type","image").put("src","/api/uploads/images/2/staff/1/private.gif");assertThrows(BusinessException.class,()->validate(d));first(d).put("src","javascript:alert(1)");assertThrows(BusinessException.class,()->validate(d));}
 @Test void noArbitraryCssOrFakeSuccessNavigation(){ObjectNode d=design();ObjectNode n=first(d);n.putObject("style").put("background-image","url(https://evil.test/tracker)");assertThrows(BusinessException.class,()->validate(d));n.remove("style");n.put("type","button").put("action","page").put("target","success");assertThrows(BusinessException.class,()->validate(d));n.put("target","detail");assertDoesNotThrow(()->validate(d));n.put("action","link").put("target","//evil.test");assertThrows(BusinessException.class,()->validate(d));}
 @Test void defaultLanguageAndRequiredPagesCannotDisappear(){ObjectNode d=design();assertThrows(BusinessException.class,()->ActivityDesignValidator.validate(d.toString(),"en",mapper));((ArrayNode)d.path("locales").path("zh-CN").path("pages")).remove(1);assertThrows(BusinessException.class,()->validate(d));}
 @Test void dialogAppearanceIsBounded(){ObjectNode d=design();d.putObject("dialog").put("width",680).put("radius",20).put("blur",6).put("backdrop","#0d1d1875");assertDoesNotThrow(()->validate(d));((ObjectNode)d.get("dialog")).put("backdrop","red;display:none");assertThrows(BusinessException.class,()->validate(d));}
}
