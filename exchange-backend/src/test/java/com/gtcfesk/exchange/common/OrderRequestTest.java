package com.gtcfesk.exchange.common;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class OrderRequestTest {
 @Test void publicKeysAndCanonicalContentNeverMergeDifferentIntents(){
  for(Object key:new Object[]{null,"","short",123,"a b c d e f g h i","1234567890123456/"})assertThrows(BusinessException.class,()->OrderRequest.required(key));
  assertEquals("valid-request-0001",OrderRequest.required("valid-request-0001"));
  String same=OrderRequest.hash("loan",2L,new BigDecimal("100.000"));
  assertEquals(same,OrderRequest.hash("loan",2L,new BigDecimal("1E+2")));
  assertNotEquals(same,OrderRequest.hash("loan",3L,new BigDecimal("100")));
  assertNotEquals(same,OrderRequest.hash("finance",2L,new BigDecimal("100")));
  OrderRequest.same(same,same);assertThrows(BusinessException.class,()->OrderRequest.same(same,"other"));
  assertNotEquals(OrderRequest.hash("a|b","c"),OrderRequest.hash("a","b|c"));
 }
 @Test void java8MapKeepsNullDuplicateAndImmutabilityGuards(){
  java.util.Map<String,Object> m=ImmutableMaps.of("a",1,"b",2);
  assertEquals(2,m.size());assertThrows(UnsupportedOperationException.class,()->m.put("c",3));
  assertThrows(IllegalArgumentException.class,()->ImmutableMaps.of("a",1,"a",2));
  assertThrows(NullPointerException.class,()->ImmutableMaps.of("a",null));
 }
}
