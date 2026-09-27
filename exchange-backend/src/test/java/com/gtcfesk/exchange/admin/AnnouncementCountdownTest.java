package com.gtcfesk.exchange.admin;
import com.gtcfesk.exchange.entity.Announcement;
import com.gtcfesk.exchange.repository.AnnouncementRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class AnnouncementCountdownTest {
 @Test void countdownValidationAndPersistence() throws Exception {
  AnnouncementRepository repo = mock(AnnouncementRepository.class);
  Announcement item = new Announcement();
  assertEquals(2, item.getCountdownSeconds());
  when(repo.findById(1L)).thenReturn(Optional.of(item));
  when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
  org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminAnnouncementController(repo)).build();
  mvc.perform(post("/api/admin/announcement/create").contentType("application/json").content("{\"countdownSeconds\":0}")).andExpect(status().isOk());
  verify(repo).save(argThat(a -> a.getCountdownSeconds() == 0));
  for (int seconds : new int[]{5, 0}) {
   mvc.perform(put("/api/admin/announcement/1").contentType("application/json").content("{\"countdownSeconds\":" + seconds + "}")).andExpect(status().isOk());
   assertEquals(seconds, item.getCountdownSeconds());
  }
  mvc.perform(put("/api/admin/announcement/1").contentType("application/json").content("{\"title\":\"keep\"}")).andExpect(status().isOk());
  assertEquals(0, item.getCountdownSeconds());
  mvc.perform(post("/api/admin/announcement/create").contentType("application/json").content("{\"countdownSeconds\":-1}")).andExpect(status().isBadRequest());
  mvc.perform(put("/api/admin/announcement/1").contentType("application/json").content("{\"countdownSeconds\":-1}")).andExpect(status().isBadRequest());
 }
}
