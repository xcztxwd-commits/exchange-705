package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.*;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.simulation.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserProfileTest {
    @TempDir Path temporary;
    UserAccountRepository users;
    UserAccount user;
    FileUploadService uploads;
    UploadStorage storage;
    UserProfileController controller;
    Authentication actor;

    @BeforeEach void setup() {
        TenantContext.open(1L);
        actor = new UsernamePasswordAuthenticationToken("77", null, Collections.singleton(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(actor);
        users = mock(UserAccountRepository.class); user = new UserAccount();
        user.setId(77L); user.setTenantId(1L); user.setEmail("user@example.com"); user.setPasswordHash("never-expose"); user.setRemark("staff only");
        when(users.findByTenantIdAndId(1L, 77L)).thenReturn(Optional.of(user));
        storage = new UploadStorage(temporary.toString()); uploads = new FileUploadService();
        ReflectionTestUtils.setField(uploads, "storage", storage);
        controller = new UserProfileController(users, uploads);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContext.clear(); }

    MockMultipartFile image() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("avatar", "../../fake.jpg", "image/png", bytes.toByteArray());
    }
    Map<?, ?> profile() { return (Map<?, ?>) controller.get(actor).getBody(); }

    @Test void responseContainsOnlyOwnIdentityAndDoesNotCachePrivateData() {
        assertEquals(new HashSet<>(Arrays.asList("id", "email", "nickname", "avatarUrl")), profile().keySet());
        assertEquals("no-store", controller.get(actor).getHeaders().getCacheControl());
        assertNull(profile().get("nickname")); assertEquals("user@example.com", profile().get("email"));
        verify(users, atLeastOnce()).findByTenantIdAndId(1L, 77L); verify(users, never()).saveAndFlush(any());
    }
    @Test void savesDecodedOwnAvatarAndNicknameWithoutChangingAccountIdentity() throws Exception {
        controller.update(actor, "  昵称😀  ", false, image());
        assertEquals("昵称😀", user.getNickname());
        assertTrue(user.getAvatarUrl().matches("/api/uploads/images/1/user/77/[a-f0-9-]+\\.png"));
        assertNotNull(ImageIO.read(storage.images().resolve(user.getAvatarUrl().replace("/api/uploads/images/", "")).toFile()));
        assertEquals("user@example.com", user.getEmail()); assertEquals("never-expose", user.getPasswordHash()); assertEquals("staff only", user.getRemark());
        verify(users).saveAndFlush(user);
    }
    @Test void clearingNicknameAndAvatarIsExplicitAndNicknameOnlyEditKeepsAvatar() throws Exception {
        controller.update(actor, "Initial", false, image()); String old = user.getAvatarUrl();
        controller.update(actor, " New ", false, null); assertEquals(old, user.getAvatarUrl());
        controller.update(actor, "\u3000\u00a0  ", true, null); assertNull(user.getNickname()); assertNull(user.getAvatarUrl());
        assertTrue(Files.exists(storage.images().resolve(old.replace("/api/uploads/images/", ""))), "old private uploads are not deleted by profile edits");
    }
    @Test void boundsUnicodeNicknameAndRejectsControlsWithoutChangingSavedData() {
        String fifty = String.join("", Collections.nCopies(50, "😀"));
        controller.update(actor, fifty, false, null); assertEquals(fifty, user.getNickname());
        clearInvocations(users);
        for (String invalid : Arrays.asList(fifty + "😀", "hello\nworld", "hello\u0000world"))
            assertThrows(BusinessException.class, () -> controller.update(actor, invalid, false, null));
        assertEquals(fifty, user.getNickname()); verify(users, never()).saveAndFlush(any());
    }
    @Test void rejectsSvgAndFakeImageAndOversizeOrEmptyFile() {
        List<MockMultipartFile> files = Arrays.asList(
            new MockMultipartFile("avatar", "evil.svg", "image/svg+xml", "<svg/>".getBytes()),
            new MockMultipartFile("avatar", "fake.png", "image/png", "not an image".getBytes()),
            new MockMultipartFile("avatar", "empty.png", "image/png", new byte[0]),
            new MockMultipartFile("avatar", "huge.png", "image/png", new byte[5 * 1024 * 1024 + 1]));
        for (MockMultipartFile file : files) assertThrows(BusinessException.class, () -> controller.update(actor, "Nickname", false, file));
        assertNull(user.getNickname()); assertNull(user.getAvatarUrl()); verify(users, never()).saveAndFlush(any());
    }
    @Test void authenticationAndTenantNeverComeFromRequestFields() throws Exception {
        assertThrows(AccessDeniedException.class, () -> controller.get(null));
        Authentication staff = new UsernamePasswordAuthenticationToken("77", null, Collections.singleton(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThrows(AccessDeniedException.class, () -> controller.update(staff, "Name", false, null));
        TenantContext.clear(); TenantContext.open(2L); assertThrows(AccessDeniedException.class, () -> controller.get(actor));
        verify(users).findByTenantIdAndId(2L, 77L); TenantContext.clear(); TenantContext.open(1L);
        MockMvcBuilders.standaloneSetup(controller).build().perform(multipart("/api/user/profile").file(image())
            .param("nickname", "Name").param("id", "99").param("email", "changed@example.com").param("avatarUrl", "https://invalid.example/secret.png")
            .principal(actor).with(request -> { request.setMethod("PUT"); return request; }))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(77)).andExpect(jsonPath("$.email").value("user@example.com"));
        verify(users, never()).findByTenantIdAndId(anyLong(), eq(99L));
        assertTrue(user.getAvatarUrl().startsWith("/api/uploads/images/1/user/77/"));
    }
    @Test void uploadAndRemovalCannotBeCombined() throws Exception {
        MockMultipartFile avatar = image();
        assertThrows(BusinessException.class, () -> controller.update(actor, "Name", true, avatar));
        verify(users, never()).saveAndFlush(any()); assertNull(user.getAvatarUrl());
    }
    @Test void avatarMigrationAddsNullableMetadataAndPreservesExistingProfiles() throws Exception {
        String sql;
        try (java.io.InputStream input = getClass().getResourceAsStream("/db/migration/V2026100601__user_avatar.sql")) {
            assertNotNull(input);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int size;
            while ((size = input.read(buffer)) != -1) bytes.write(buffer, 0, size);
            sql = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (java.sql.Connection db = java.sql.DriverManager.getConnection("jdbc:h2:mem:avatar_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
             java.sql.Statement statement = db.createStatement()) {
            statement.execute("create table user_account(id bigint primary key,email varchar(128),nickname varchar(50))");
            statement.execute("insert into user_account values(77,'user@example.com','Existing name')");
            statement.execute(sql);
            try (java.sql.ResultSet rows = statement.executeQuery("select id,email,nickname,avatar_url from user_account")) {
                assertTrue(rows.next()); assertEquals(77, rows.getLong("id")); assertEquals("user@example.com", rows.getString("email"));
                assertEquals("Existing name", rows.getString("nickname")); assertNull(rows.getString("avatar_url")); assertFalse(rows.next());
            }
        }
    }
    @Test void demoEnvironmentCannotMutateOrServeAnIndependentProfile() throws Exception {
        SimulationEnvironment environment = mock(SimulationEnvironment.class); when(environment.enabled()).thenReturn(true);
        SimulationIdentityBoundary boundary = new SimulationIdentityBoundary(environment);
        for (String method : Arrays.asList("GET", "PUT")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            boundary.doFilter(new MockHttpServletRequest(method, "/api/user/profile"), response, chain);
            assertEquals(409, response.getStatus()); assertNull(chain.getRequest());
        }
        when(environment.enabled()).thenReturn(false);
        MockFilterChain chain = new MockFilterChain(); boundary.doFilter(new MockHttpServletRequest("GET", "/api/user/profile"), new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
    }
}
