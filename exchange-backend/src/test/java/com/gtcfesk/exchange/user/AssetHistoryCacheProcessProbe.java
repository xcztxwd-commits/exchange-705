package com.gtcfesk.exchange.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.config.AssetHistoryRedisConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Paths;
import java.util.*;

/** A genuinely fresh JVM, not a cleared in-process map. Launched only by the isolated acceptance test. */
public class AssetHistoryCacheProcessProbe {
    public static void main(String[] args)throws Exception{
        DriverManagerDataSource source=com.gtcfesk.exchange.tenant.DedicatedMysqlFixture.fromProperty("cache.mysql.fixture");
        com.gtcfesk.exchange.tenant.TenantContext.open(2L);
        ObjectMapper json=new ObjectMapper();
        RedisProperties p=new RedisProperties();p.setHost("127.0.0.1");p.setPort(Integer.parseInt(System.getenv("CACHE_TEST_REDIS_PORT")));
        AssetHistoryRedisConfig config=new AssetHistoryRedisConfig();
        try{
            StringRedisTemplate redis=config.assetHistoryRedis(p,200);redis.afterPropertiesSet();
            AssetHistoryCache cache=new AssetHistoryCache(redis,json);cache.enabled=true;cache.namespace=System.getenv("CACHE_TEST_NAMESPACE");
            AssetEquityStore store=new AssetEquityStore(source,json);store.cache=cache;
            AssetEquityHistoryService api=new AssetEquityHistoryService(store,new EquityValuationService(new JdbcTemplate(source),null));
            Map<String,Object> out=new LinkedHashMap<>();out.put("response",api.history(1L,"1W",new EquityValuationService.Batch(),AssetHistoryCacheIT.NOW));out.put("hits",cache.hits.sum());
            json.writeValue(Paths.get(System.getenv("CACHE_TEST_REPORT"),"fresh-process.json").toFile(),out);
        }finally{config.close();}
    }
}
