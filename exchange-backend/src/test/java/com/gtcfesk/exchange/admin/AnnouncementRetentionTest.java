package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.Announcement;
import com.gtcfesk.exchange.repository.AnnouncementRepository;
import com.gtcfesk.exchange.user.AnnouncementController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class AnnouncementRetentionTest {
    @Test void deleteHidesWithoutBreakingPublishedHistoryOrReceipts() {
        AnnouncementRepository repo=mock(AnnouncementRepository.class);
        Announcement item=new Announcement();item.setId(7L);item.setTitle("history");item.setContent("unchanged");
        when(repo.findByTenantIdAndId(1L,7L)).thenReturn(Optional.of(item));
        AdminAnnouncementController admin=new AdminAnnouncementController(repo);
        assertEquals(200,admin.deleteAnnouncement(7L).getStatusCodeValue());
        assertEquals("HIDDEN",item.getStatus());assertEquals(7L,item.getId());assertEquals("unchanged",item.getContent());
        assertEquals(400,new AnnouncementController(repo).getAnnouncement(7L).getStatusCodeValue());
        assertEquals(200,admin.deleteAnnouncement(7L).getStatusCodeValue());
        verify(repo,times(2)).saveAndFlush(item);
        verify(repo,never()).deleteByTenantIdAndId(anyLong(),anyLong());verify(repo,never()).delete(any());
    }
    @Test void missingOrForeignIdCannotBeSavedOrDeleted() {
        AnnouncementRepository repo=mock(AnnouncementRepository.class);
        when(repo.findByTenantIdAndId(1L,9L)).thenReturn(Optional.empty());
        assertEquals(400,new AdminAnnouncementController(repo).deleteAnnouncement(9L).getStatusCodeValue());
        verify(repo).findByTenantIdAndId(1L,9L);verify(repo,never()).saveAndFlush(any());verify(repo,never()).deleteByTenantIdAndId(anyLong(),anyLong());
    }
}
