package com.gtcfesk.exchange.admin;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
/** Shared presentation-only input contract for two separate identity domains. */
public final class TablePreferenceValidation {
 private TablePreferenceValidation(){}
 public static String table(String table){
  if(table==null||!table.matches("[A-Za-z0-9_.-]{1,100}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"表格标识无效");
  return table;
 }
 public static void columns(JsonNode columns){
  if(columns==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"列配置无效");
        if (!columns.isArray() || columns.size() > 150 || columns.toString().length() > 60000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "列配置无效");
        Set<String> ids = new HashSet<>();
        boolean visible = columns.size() == 0;
        for (JsonNode column : columns) {
            if (!column.isObject() || column.size() != 3 || !column.path("id").isTextual() ||
                    column.path("id").asText().isEmpty() || column.path("id").asText().length() > 200 ||
                    !ids.add(column.path("id").asText()) || !column.path("visible").isBoolean() ||
                    !column.path("fixed").isTextual() || !Arrays.asList("", "left", "right").contains(column.path("fixed").asText()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "列配置无效");
            visible |= column.path("visible").asBoolean();
        }
        if (!visible) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少保留一列");
 }
}
