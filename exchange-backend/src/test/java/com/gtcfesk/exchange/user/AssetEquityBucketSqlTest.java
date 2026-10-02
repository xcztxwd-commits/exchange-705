package com.gtcfesk.exchange.user;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
/** SQL-shape regression; actual MySQL semantics remain covered by the mandatory ITs. */
class AssetEquityBucketSqlTest {
 @Test void tenantIsInsertKeyNotASecondValuesArgument(){
  JdbcTemplate db=mock(JdbcTemplate.class);AssetEquityStore store=new AssetEquityStore(null,new ObjectMapper());
  org.mockito.ArgumentCaptor<String> sql=org.mockito.ArgumentCaptor.forClass(String.class);
  try(TenantContext.Scope scope=TenantContext.open(2L)){store.saveBucket(db,1,new AssetHistoryBucket(11,0,3600000),1);}
  assertEquals(1,mockingDetails(db).getInvocations().size());org.mockito.invocation.Invocation call=mockingDetails(db).getInvocations().iterator().next();String value=call.getArgument(0);assertEquals(20,((Object[])call.getRawArguments()[1]).length);
  assertTrue(value.contains("(tenant_id,user_id,basis_version,bucket_start,"));assertTrue(value.contains("values(2,"));
  String updates=value.substring(value.indexOf("on duplicate key update"));assertFalse(updates.contains("values(2,"));
  assertTrue(updates.contains("bucket_end=if(finalized=0 or values(finalized)=1,values(bucket_end),bucket_end)"));
 }
}
