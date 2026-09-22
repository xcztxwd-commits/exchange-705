package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;

/** Project taxonomy controls presentation; source bindings are defaults for new instruments only. */
@Service
public class MarketCategoryService {
    @Autowired SystemConfigService configs;
    public List<Map<String,Object>> all(){
        String raw=configs.getConfigValue("home.categories");List<Map<String,Object>> rows=new ArrayList<>();
        if(raw!=null&&!raw.trim().isEmpty())try{rows=ExchangeQuoteSource.JSON.readValue(raw,new TypeReference<List<Map<String,Object>>>(){});}catch(Exception e){throw new BusinessException("分类配置格式无效");}
        if(rows.isEmpty())for(String key:Arrays.asList("US","Forex","Metal","Crypto","CFD","Oil")){
            Map<String,Object> row=new LinkedHashMap<>();row.put("key",key);row.put("label",key);row.put("sortOrder",rows.size()+1);row.put("enabled",true);rows.add(row);
        }
        for(Map<String,Object> row:rows){String key=String.valueOf(row.get("key"));
            row.putIfAbsent("leverageEnabled",true);
            if(!row.containsKey("marketSource")){row.put("marketSource",MarketInstrumentCatalog.inferredSource(key));row.put("sourceCategory",key);}
        }
        rows.sort(Comparator.comparingInt(row->((Number)row.getOrDefault("sortOrder",0)).intValue()));return rows;
    }
    public List<Map<String,Object>> save(List<Map<String,Object>> input){
        if(input==null||input.isEmpty()||input.size()>30)throw new BusinessException("请保留 1–30 个项目分类");
        Set<String> keys=new HashSet<>();List<Map<String,Object>> rows=new ArrayList<>();
        for(Map<String,Object> row:input){
            String key=Objects.toString(row.get("key"),""),label=Objects.toString(row.get("label"),"");
            if(!key.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")||!keys.add(key)||label.trim().isEmpty()||label.length()>64)throw new BusinessException("分类标识或名称无效、重复");
            String source=(String)row.get("marketSource"),category=(String)row.get("sourceCategory");
            MarketInstrumentCatalog.validate(source,category);
            Map<String,Object> clean=new LinkedHashMap<>();clean.put("key",key);clean.put("label",label);clean.put("sortOrder", Math.max(0, Math.min(999, ((Number)row.getOrDefault("sortOrder", rows.size()+1)).intValue())));
            if(row.containsKey("leverageEnabled") && !(row.get("leverageEnabled") instanceof Boolean)) throw new BusinessException("允许杠杆必须为开关值");
            clean.put("leverageEnabled",!Boolean.FALSE.equals(row.get("leverageEnabled")));
            clean.put("enabled",!Boolean.FALSE.equals(row.get("enabled")));clean.put("marketSource",source);clean.put("sourceCategory",category);rows.add(clean);
        }
        // Existing project classifications cannot disappear underneath existing instruments.
        for(Map<String,Object> old:all())if(!keys.contains(old.get("key")))throw new BusinessException("现有分类请关闭显示，不要直接移除");
        try{configs.saveConfig("home.categories",ExchangeQuoteSource.JSON.writeValueAsString(rows),"项目分类与行情源分类绑定");}catch(Exception e){throw new BusinessException("分类保存失败");}
        return rows;
    }
    public boolean leverageEnabled(String category){
        return all().stream().noneMatch(row->Objects.equals(category,row.get("key")) && Boolean.FALSE.equals(row.get("leverageEnabled")));
    }
    public String projectCategory(String requested,String source,String sourceCategory){
        List<Map<String,Object>> rows=all();
        if(requested!=null&&!requested.isEmpty()){
            if(rows.stream().noneMatch(row->requested.equals(row.get("key"))))throw new BusinessException("项目分类不存在，请刷新后重试");
            return requested;
        }
        List<String> matched=new ArrayList<>();for(Map<String,Object> row:rows)
            if(source.equals(row.get("marketSource"))&&sourceCategory.equals(row.get("sourceCategory")))matched.add((String)row.get("key"));
        if(matched.size()!=1)throw new BusinessException("请手动选择项目分类");return matched.get(0);
    }
}
