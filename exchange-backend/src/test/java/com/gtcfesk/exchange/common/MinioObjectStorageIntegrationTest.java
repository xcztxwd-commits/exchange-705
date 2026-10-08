package com.gtcfesk.exchange.common;

import io.minio.*;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.util.StreamUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in round trip against an isolated loopback MinIO, never a production endpoint. */
@EnabledIfEnvironmentVariable(named = "VIDEO_TEST_MINIO_ENDPOINT", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class MinioObjectStorageIntegrationTest {
    @Test void privateBucketRoundTripAndPartialRead() throws Exception {
        String endpoint = System.getenv("VIDEO_TEST_MINIO_ENDPOINT"), key = System.getenv("VIDEO_TEST_MINIO_ACCESS_KEY"), secret = System.getenv("VIDEO_TEST_MINIO_SECRET_KEY");
        String bucket = "video-test-" + UUID.randomUUID();
        MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(key, secret).build();
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        String object = "videos/42/staff/7/" + UUID.randomUUID() + ".mp4";
        byte[] bytes = new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m',0,0,0,1};
        try {
            MinioObjectStorage storage = new MinioObjectStorage(endpoint, key, secret, bucket);
            storage.put(object, new ByteArrayInputStream(bytes), bytes.length, "video/mp4");
            assertEquals(bytes.length, storage.stat(object).size());
            assertEquals("video/mp4", storage.stat(object).contentType());
            try (java.io.InputStream stream = storage.read(object, 0, bytes.length)) { assertArrayEquals(bytes, StreamUtils.copyToByteArray(stream)); }
            try (java.io.InputStream stream = storage.read(object, 4, 4)) { assertArrayEquals(new byte[]{'f','t','y','p'}, StreamUtils.copyToByteArray(stream)); }
            java.net.HttpURLConnection anonymous = (java.net.HttpURLConnection) new java.net.URL(endpoint + "/" + bucket + "/real/" + object).openConnection();
            try { assertEquals(403, anonymous.getResponseCode()); } finally { anonymous.disconnect(); }
        } finally {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object("real/" + object).build());
            client.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
        }
    }

    @Test void multipartSignaturesGifAudioAndAuthorizedRangesUsePrivateMinio() throws Exception {
        String endpoint = System.getenv("VIDEO_TEST_MINIO_ENDPOINT"), key = System.getenv("VIDEO_TEST_MINIO_ACCESS_KEY"), secret = System.getenv("VIDEO_TEST_MINIO_SECRET_KEY");
        String bucket = "media-test-" + UUID.randomUUID();
        MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(key, secret).build();
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("unused-local-media-");
        try {
            com.gtcfesk.exchange.tenant.TenantContext.open(987654321L);
            org.springframework.security.core.Authentication actor = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("77", null,
                    java.util.Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(actor);
            MinioObjectStorage objects = new MinioObjectStorage(endpoint, key, secret, bucket);
            UploadStorage storage = new UploadStorage(root.toString());
            org.springframework.test.util.ReflectionTestUtils.setField(storage, "objects", objects);
            org.springframework.test.util.ReflectionTestUtils.setField(storage, "mode", "minio");
            FileUploadController uploads = new FileUploadController();
            com.gtcfesk.exchange.user.FileUploadService signatures = new com.gtcfesk.exchange.user.FileUploadService();
            ImageController reader = new ImageController();
            for (Object component : java.util.Arrays.asList(uploads, signatures, reader)) org.springframework.test.util.ReflectionTestUtils.setField(component, "storage", storage);
            org.springframework.test.util.ReflectionTestUtils.setField(reader, "published", org.mockito.Mockito.mock(PublishedTenantFiles.class));
            org.springframework.test.util.ReflectionTestUtils.setField(reader, "access", org.mockito.Mockito.mock(com.gtcfesk.exchange.config.BackendAccess.class));
            org.springframework.test.util.ReflectionTestUtils.setField(reader, "audit", org.mockito.Mockito.mock(com.gtcfesk.exchange.control.ControlAuditService.class));
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
            assertTrue(javax.imageio.ImageIO.write(image, "png", encoded));
            byte[] png = encoded.toByteArray();
            org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile("file", "image.png", "image/png", png);
            String url = (String) ((java.util.Map<?, ?>) uploads.uploadImage(actor, file).getBody()).get("url");
            String signature = signatures.uploadBase64Image("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png));
            byte[] gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
            String gifUrl = signatures.uploadImage(new org.springframework.mock.web.MockMultipartFile("file", "animation.gif", "image/gif", gif));
            byte[] wav = "RIFF0000WAVEpayload".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            String audio = (String) ((java.util.Map<?, ?>) uploads.uploadAudio(actor, new org.springframework.mock.web.MockMultipartFile("file", "sound.wav", "audio/wav", wav)).getBody()).get("url");
            for (String path : java.util.Arrays.asList(url, signature, gifUrl, audio)) {
                org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> response = reader.getFile(new org.springframework.mock.web.MockHttpServletRequest("GET", path));
                assertEquals(200, response.getStatusCodeValue());
                try (java.io.InputStream stream = response.getBody().getInputStream()) {
                    assertArrayEquals(path.equals(audio) ? wav : path.equals(gifUrl) ? gif : png, StreamUtils.copyToByteArray(stream));
                }
                java.net.HttpURLConnection anonymous = (java.net.HttpURLConnection) new java.net.URL(endpoint + "/" + bucket + "/real/" + path.substring("/api/uploads/".length())).openConnection();
                try { assertEquals(403, anonymous.getResponseCode()); } finally { anonymous.disconnect(); }
            }
            org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(reader).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(audio).header("Range", "bytes=0-7"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPartialContent())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(java.util.Arrays.copyOf(wav, 8)));
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("78", null, actor.getAuthorities()));
            assertEquals(404, reader.getFile(new org.springframework.mock.web.MockHttpServletRequest("GET", url)).getStatusCodeValue());
            com.gtcfesk.exchange.tenant.TenantContext.clear(); com.gtcfesk.exchange.tenant.TenantContext.open(987654322L);
            assertEquals(404, reader.getFile(new org.springframework.mock.web.MockHttpServletRequest("GET", url)).getStatusCodeValue());
            MinioObjectStorage demo = new MinioObjectStorage(endpoint, key, secret, bucket);
            org.springframework.test.util.ReflectionTestUtils.setField(demo, "prefix", "demo");
            assertThrows(io.minio.errors.ErrorResponseException.class, () -> demo.stat(url.substring("/api/uploads/".length())));
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(root)) { assertEquals(0, paths.filter(java.nio.file.Files::isRegularFile).count()); }
            MinioObjectStorage unavailable = org.mockito.Mockito.mock(MinioObjectStorage.class);
            org.mockito.Mockito.doThrow(new java.io.IOException("offline")).when(unavailable).put(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
            org.springframework.test.util.ReflectionTestUtils.setField(storage, "objects", unavailable);
            assertEquals(400, uploads.uploadImage(actor, file).getStatusCodeValue());
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(root)) { assertEquals(0, paths.filter(java.nio.file.Files::isRegularFile).count()); }
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext(); com.gtcfesk.exchange.tenant.TenantContext.clear();
            for (io.minio.Result<io.minio.messages.Item> item : client.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(item.get().objectName()).build());
            client.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
            java.nio.file.Files.delete(root);
        }
    }
}
