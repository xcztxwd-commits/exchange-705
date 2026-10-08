package com.gtcfesk.exchange.common;

import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.tenant.TenantContext;
import io.minio.StatObjectResponse;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoControllerTest {
    private final MinioObjectStorage storage = mock(MinioObjectStorage.class);
    private final SystemConfigService configs = mock(SystemConfigService.class);
    private final AdminPermissionService permissions = mock(AdminPermissionService.class);
    private final TenantPolicyService policy = mock(TenantPolicyService.class);
    private final VideoController controller = new VideoController(storage, configs, permissions, policy);
    private final String url = "/api/uploads/videos/42/staff/7/00000000-0000-0000-0000-000000000001.mp4";
    private final byte[] mp4 = new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,1};

    @BeforeEach void setup() {
        TenantContext.open(42L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("7", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    @AfterEach void cleanup() { TenantContext.clear(); SecurityContextHolder.clearContext(); }
    private String settings() { return "{\"defaultLocale\":\"en\",\"videos\":{\"en\":\"" + url + "\"}}"; }

    @Test void uploadsStreamToMinioWithTenantOwnerAndGeneratedName() throws Exception {
        doAnswer(call -> {
            assertTrue(((String)call.getArgument(0)).matches("videos/42/staff/7/[0-9a-f-]{36}\\.mp4"));
            assertArrayEquals(mp4, org.springframework.util.StreamUtils.copyToByteArray((InputStream)call.getArgument(1)));
            assertEquals((long)mp4.length, (Long)call.getArgument(2));
            assertEquals("video/mp4", call.getArgument(3)); return null;
        }).when(storage).put(anyString(), any(), anyLong(), anyString());
        Map<String, Object> result = controller.upload(new MockMultipartFile("file", "../../INTRO.MP4", "video/mp4", mp4));
        assertEquals(true, result.get("success"));
        assertTrue(VideoIntroSettings.videoPath((String)result.get("url"), 42L));
        verify(permissions).require("settings", "save"); verify(policy).requireConfigChange(VideoIntroSettings.KEY, null);
        verify(storage).put(anyString(), any(), anyLong(), eq("video/mp4"));
    }

    @Test void invalidEmptyOversizedAndUnauthorizedUploadsNeverReachMinio() throws Exception {
        for (MockMultipartFile file : new MockMultipartFile[]{
                new MockMultipartFile("file", "intro.mp4", "video/mp4", new byte[0]),
                new MockMultipartFile("file", "intro.mp4", "video/mp4", "not a video".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("file", "intro.exe", "video/mp4", mp4),
                new MockMultipartFile("file", "intro.mp4", "text/html", mp4)})
            assertThrows(BusinessException.class, () -> controller.upload(file));
        MockMultipartFile big = new MockMultipartFile("file", "intro.mp4", "video/mp4", mp4) {
            @Override public long getSize() { return VideoController.MAX_VIDEO_SIZE + 1; }
        };
        assertThrows(BusinessException.class, () -> controller.upload(big));
        doThrow(new AccessDeniedException("无权执行此操作")).when(permissions).require("settings", "save");
        assertThrows(AccessDeniedException.class, () -> controller.upload(new MockMultipartFile("file", "intro.mp4", "video/mp4", mp4)));
        verifyNoInteractions(storage);
    }

    @Test void publishedVideoAllowsAnonymousRangesAndHeadButRejectsForeignAndUnpublishedPaths() throws Exception {
        when(configs.getConfigValue(VideoIntroSettings.KEY)).thenReturn(settings());
        StatObjectResponse info = mock(StatObjectResponse.class); when(info.size()).thenReturn(16L);
        when(storage.stat("videos/" + url.substring(VideoIntroSettings.URL_PREFIX.length()))).thenReturn(info);
        when(storage.read(anyString(), eq(4L), eq(4L))).thenReturn(new ByteArrayInputStream(new byte[]{'f','t','y','p'}));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", url); request.addHeader("Range", "bytes=4-7");
        MockHttpServletResponse response = new MockHttpServletResponse(); controller.video(request, response, null);
        assertEquals(206, response.getStatus()); assertEquals("bytes 4-7/16", response.getHeader("Content-Range"));
        assertArrayEquals(new byte[]{'f','t','y','p'}, response.getContentAsByteArray());
        assertEquals("video/mp4", response.getContentType()); assertEquals("bytes", response.getHeader("Accept-Ranges"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        for (String range : new String[]{"bytes=99-100", "bytes=4-2", "bytes=0-1,4-5", "bytes=-0", "invalid"}) {
            request = new MockHttpServletRequest("GET", url); request.addHeader("Range", range);
            response = new MockHttpServletResponse(); controller.video(request, response, null);
            assertEquals(416, response.getStatus()); assertEquals("bytes */16", response.getHeader("Content-Range"));
        }
        response = new MockHttpServletResponse(); controller.video(new MockHttpServletRequest("HEAD", url), response, null);
        assertEquals(200, response.getStatus()); assertEquals("16", response.getHeader("Content-Length"));
        assertEquals(0, response.getContentAsByteArray().length);
        for (String path : new String[]{url.replace("/42/", "/43/"), url.replace("000000000001", "000000000002"),
                url.replace("/staff/", "/user/"), url + "/../other.mp4"}) {
            response = new MockHttpServletResponse(); controller.video(new MockHttpServletRequest("GET", path), response, null);
            assertEquals(404, response.getStatus());
        }
        verify(storage, times(1)).read(anyString(), anyLong(), anyLong());
    }

    @Test void onlyAuthorizedUploaderCanPreviewUnpublishedVideo() throws Exception {
        StatObjectResponse info = mock(StatObjectResponse.class); when(info.size()).thenReturn(16L);
        when(storage.stat(anyString())).thenReturn(info);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.video(new MockHttpServletRequest("HEAD", url), response, SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, response.getStatus());
        response = new MockHttpServletResponse();
        controller.video(new MockHttpServletRequest("HEAD", url.replace("/7/", "/8/")), response, SecurityContextHolder.getContext().getAuthentication());
        assertEquals(404, response.getStatus());
        doThrow(new AccessDeniedException("无权访问")).when(permissions).require("settings", "");
        response = new MockHttpServletResponse();
        controller.video(new MockHttpServletRequest("HEAD", url), response, SecurityContextHolder.getContext().getAuthentication());
        assertEquals(404, response.getStatus());
        verify(storage, times(1)).stat(anyString());
    }

    @Test void languageFallsBackAndEmptySettingsDisableLegacyVideo() {
        when(configs.getConfigValue(VideoIntroSettings.KEY)).thenReturn(settings());
        assertEquals(url, controller.introduction("en-US").get("url"));
        assertEquals("en", controller.introduction("ja").get("language"));
        when(configs.getConfigValue(SystemConfigService.VIDEO_INTRO_URL_KEY)).thenReturn("https://cdn.example.com/old.mp4");
        when(configs.getConfigValue(VideoIntroSettings.KEY)).thenReturn("{\"defaultLocale\":\"en\",\"videos\":{}}");
        assertEquals("", controller.introduction("en").get("url"));
        when(configs.getConfigValue(VideoIntroSettings.KEY)).thenReturn(null);
        assertEquals("https://cdn.example.com/old.mp4", controller.introduction("en").get("url"));
    }

    @Test void missingOrFailedMinioNeverReturnsAnUploadSuccess() throws Exception {
        doThrow(new java.io.IOException("unreachable")).when(storage).put(anyString(), any(), anyLong(), anyString());
        assertThrows(BusinessException.class, () -> controller.upload(new MockMultipartFile("file", "intro.mp4", "video/mp4", mp4)));
        assertThrows(BusinessException.class, () -> new MinioObjectStorage("", "", "", "exchange-media").put("videos/test", new ByteArrayInputStream(mp4), mp4.length, "video/mp4"));
    }
}
