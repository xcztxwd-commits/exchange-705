package com.gtcfesk.exchange.common;

import io.minio.*;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** All media use the private bucket and a separate prefix per runtime. */
@Component
public class MinioObjectStorage {
    private final MinioClient client;
    private final String bucket;
    @Value("${minio.prefix:real}") private String prefix = "real";

    public MinioObjectStorage(@Value("${minio.endpoint:}") String endpoint,
                            @Value("${minio.access-key:}") String accessKey,
                            @Value("${minio.secret-key:}") String secretKey,
                            @Value("${minio.bucket:exchange-media}") String bucket) {
        this.bucket = bucket;
        client = endpoint.trim().isEmpty() || accessKey.trim().isEmpty() || secretKey.trim().isEmpty()
                ? null : MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        if (client != null) client.setTimeout(10000, 300000, 300000);
    }

    private MinioClient client() {
        if (client == null) throw new BusinessException("MinIO 尚未配置，请联系管理员配置素材存储");
        return client;
    }

    private String objectPath(String object) {
        if (!prefix.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")) throw new BusinessException("MinIO 存储前缀配置无效");
        return prefix + "/" + object;
    }

    public void put(String object, InputStream stream, long size, String contentType) throws Exception {
        client().putObject(PutObjectArgs.builder().bucket(bucket).object(objectPath(object))
                .stream(stream, size, -1).contentType(contentType).build());
    }

    public StatObjectResponse stat(String object) throws Exception {
        return client().statObject(StatObjectArgs.builder().bucket(bucket).object(objectPath(object)).build());
    }

    public InputStream read(String object, long offset, long length) throws Exception {
        return client().getObject(GetObjectArgs.builder().bucket(bucket).object(objectPath(object)).offset(offset).length(length).build());
    }
}
