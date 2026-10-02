package com.gtcfesk.exchange.activity;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;
@Converter
public class ActivityStringListConverter implements AttributeConverter<List<String>,String> {
 private static final ObjectMapper JSON=new ObjectMapper();
 public String convertToDatabaseColumn(List<String> value){try{return JSON.writeValueAsString(value==null?Collections.emptyList():value);}catch(Exception e){throw new IllegalArgumentException("活动枚举序列化失败",e);}}
 public List<String> convertToEntityAttribute(String value){if(value==null||value.isEmpty())return new ArrayList<>();try{return JSON.readValue(value,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalArgumentException("活动枚举数据无效",e);}}
}
