package com.gtcfesk.exchange.support;

import com.gtcfesk.exchange.admin.AdminUserIdentity;
import com.gtcfesk.exchange.entity.Announcement;
import com.gtcfesk.exchange.repository.AnnouncementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.context.ContextConfiguration;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Reuses real JWT, host, permission and support regressions without editing their owner fixture. */
@ContextConfiguration(classes=UnifiedInboxSecurityTest.InboxWeb.class)
class UnifiedInboxSecurityTest extends SupportHttpTest {
    @Configuration
    @Import({AdminUserIdentity.class,UnifiedInboxService.class,UnifiedInboxController.class})
    static class InboxWeb {
        @Bean static BeanPostProcessor inboxEntities(){return new BeanPostProcessor(){
            @Override public Object postProcessBeforeInitialization(Object bean,String name){
                if(bean instanceof LocalContainerEntityManagerFactoryBean)
                    ((LocalContainerEntityManagerFactoryBean)bean).setPackagesToScan("com.gtcfesk.exchange.entity","com.gtcfesk.exchange.admin","com.gtcfesk.exchange.support","com.gtcfesk.exchange.control","com.gtcfesk.exchange.activity");
                return bean;
            }
        };}
    }
    @Autowired AnnouncementRepository announcements;
    @Test void unifiedEndpointUsesRealJwtHostAndExistingSenderPermissions()throws Exception {
        String path="/api/user/support/unified-inbox";
        Announcement a=new Announcement();a.setTitle("Security fixture");a.setContent("public");a=announcements.saveAndFlush(a);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+adminToken)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Host","unknown.mt705.test").header("Authorization","Bearer "+userToken)).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization","Bearer "+userToken)).andExpect(status().isOk());
        String body="{\"requestId\":\""+UUID.randomUUID()+"\",\"users\":["+userId+"],\"title\":\"Private delivery\",\"content\":\"Recipient only\"}";
        mvc.perform(post("/api/admin/support/inbox").header("Authorization","Bearer "+limitedToken).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/support/inbox").header("Authorization","Bearer "+adminToken).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(1));
        String json=mvc.perform(get(path).header("Authorization","Bearer "+userToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id="";for(com.fasterxml.jackson.databind.JsonNode row:mapper.readTree(json).path("content"))if("LETTER".equals(row.path("type").asText())&&"Private delivery".equals(row.path("title").asText()))id=row.path("id").asText();
        org.junit.jupiter.api.Assertions.assertFalse(id.isEmpty());
        mvc.perform(post(path+"/"+id+"/read").header("Authorization","Bearer "+secondToken)).andExpect(status().isNotFound());
        mvc.perform(post(path+"/"+id+"/read").header("Authorization","Bearer "+userToken)).andExpect(status().isOk()).andExpect(jsonPath("$.readAt").exists());
    }
}
