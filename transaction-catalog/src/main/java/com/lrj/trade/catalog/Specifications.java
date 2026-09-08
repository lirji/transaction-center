package com.lrj.trade.catalog;

import com.lrj.trade.core.Checks;
import com.lrj.trade.core.TradeException;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 规格使用有界字符串键值和规范序列化，属性顺序变化不产生不同幂等请求。 */
final class Specifications {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private Specifications() {}
    static String encode(Map<String,String> specification) {
        if(specification==null || specification.size()>16) throw new TradeException("INVALID_ARGUMENT","规格必须提供且最多十六项");
        specification.forEach((key,value)->{ Checks.text(key,64); Checks.text(value,128); });
        return JSON.writeValueAsString(new TreeMap<>(specification));
    }
    static Map<String,String> decode(String json) {
        return Map.copyOf(JSON.readValue(json,new TypeReference<Map<String,String>>() {}));
    }
}
