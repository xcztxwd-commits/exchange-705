package com.gtcfesk.exchange.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

/** Separate bounded client; does not alter market/settlement Redis timeout behavior. */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="asset.history.cache",name="enabled",havingValue="true")
public class AssetHistoryRedisConfig {
    private LettuceConnectionFactory factory;
    @javax.annotation.PreDestroy public void close(){if(factory!=null)factory.destroy();}
    @Bean("assetHistoryRedis")
    public StringRedisTemplate assetHistoryRedis(RedisProperties properties,
            @Value("${asset.history.cache.timeout-ms:200}") long timeout){
        if(properties.getSentinel()!=null || properties.getCluster()!=null || properties.getUrl()!=null)
            throw new IllegalArgumentException("History cache requires explicit standalone Redis host/port configuration");
        RedisStandaloneConfiguration server=new RedisStandaloneConfiguration(properties.getHost(),properties.getPort());
        server.setDatabase(properties.getDatabase());server.setUsername(properties.getUsername());
        if(properties.getPassword()!=null)server.setPassword(properties.getPassword());
        Duration bound=Duration.ofMillis(Math.max(25,Math.min(timeout,1000)));
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client=LettuceClientConfiguration.builder().commandTimeout(bound).shutdownTimeout(Duration.ZERO)
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(bound).build()).build());
        if(properties.isSsl())client.useSsl();
        factory=new LettuceConnectionFactory(server,client.build());factory.afterPropertiesSet();
        return new StringRedisTemplate(factory);
    }
}
