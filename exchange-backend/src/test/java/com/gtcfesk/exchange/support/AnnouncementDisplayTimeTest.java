package com.gtcfesk.exchange.support;

import com.gtcfesk.exchange.admin.AdminAnnouncementController;
import com.gtcfesk.exchange.entity.Announcement;
import com.gtcfesk.exchange.repository.AnnouncementRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.user.AnnouncementController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(UnifiedInboxFixture.class)
class AnnouncementDisplayTimeTest {
    @Autowired AnnouncementRepository repository;
    @Autowired AdminAnnouncementController admin;
    @Autowired AnnouncementController publicApi;
    @Test void realPersistenceRestSerializationSortingAndLegacyFallback() throws Exception {
        try(TenantContext.Scope ignored=TenantContext.open(991234L)) {
            Announcement legacy=new Announcement();legacy.setTitle("Legacy");legacy.setContent("original");legacy=repository.saveAndFlush(legacy);
            assertEquals(legacy.getCreatedAt(),legacy.getDisplayAt());
            LocalDateTime created=repository.findByTenantIdAndId(991234L,legacy.getId()).get().getCreatedAt();int countdown=legacy.getCountdownSeconds();
            MockMvc mvc=MockMvcBuilders.standaloneSetup(admin,publicApi).build();
            mvc.perform(put("/api/admin/announcement/"+legacy.getId()).contentType("application/json").content("{\"displayAt\":\"2031-01-02T03:04:05\"}")).andExpect(status().isOk());
            Announcement after=repository.findByTenantIdAndId(991234L,legacy.getId()).get();
            assertEquals(created,after.getCreatedAt());assertEquals(countdown,after.getCountdownSeconds());assertEquals(LocalDateTime.of(2031,1,2,3,4,5),after.getDisplayAt());
            mvc.perform(put("/api/admin/announcement/"+legacy.getId()).contentType("application/json").content("{\"displayAt\":\"not-a-date\"}")).andExpect(status().isBadRequest());
            assertEquals(legacy.getId(),repository.findLatestPublishedByLanguage("en").get().getId());
            mvc.perform(get("/api/user/announcements").param("language","fr")).andExpect(status().isOk()).andExpect(jsonPath("$.announcements[0].title").value("Legacy"));
            mvc.perform(get("/api/user/announcements/latest").param("language","fr")).andExpect(status().isOk()).andExpect(jsonPath("$.announcement.title").value("Legacy"));
            mvc.perform(get("/api/user/announcements/"+legacy.getId())).andExpect(status().isOk()).andExpect(jsonPath("$.announcement.displayAt").exists());
        }
        try(TenantContext.Scope ignored=TenantContext.open(991235L)){assertTrue(repository.findAllPublished().isEmpty());}
    }
}
