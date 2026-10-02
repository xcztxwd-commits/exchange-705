package com.gtcfesk.exchange.common;
import java.util.*;
/** Java 8 replacement for the existing fixed-key Map.of responses, preserving fail-closed guards. */
public final class ImmutableMaps {
 private ImmutableMaps() {}
 public static Map<String,Object> of(Object... pairs) {
  if(pairs.length%2!=0)throw new IllegalArgumentException("Uneven map pairs");
  Map<String,Object> result=new LinkedHashMap<>();
  for(int i=0;i<pairs.length;i+=2){String key=(String)Objects.requireNonNull(pairs[i]);Object value=Objects.requireNonNull(pairs[i+1]);if(result.containsKey(key))throw new IllegalArgumentException("Duplicate map key");result.put(key,value);}
  return Collections.unmodifiableMap(result);
 }
}
