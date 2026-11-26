package com.lrj.trade.core.query;

import com.lrj.trade.core.TradeException;
import java.time.*;
import java.util.*;

/** 解析后的不可变查询条件；SQL只消费白名单字段，绝不拼接调用方列名。 */
public record AdminQuery(long after,int limit,Instant createdFrom,Instant createdTo,Map<String,Object> filters,int fetchLimit) {
    private static final Set<String> COMMON=Set.of("after","limit","createdFrom","createdTo");
    private static final Duration MAX_WINDOW=Duration.ofDays(90);
    /** 业务时间字段按资源显式传入，公共解析器不接受任意列名。 */
    public record WindowKeys(String from,String to) {
        private boolean contains(String key) { return from.equals(key) || to.equals(key); }
    }
    /** 保留五参领域调用；探测条数只能由合法页大小推导。 */
    public AdminQuery(long after,int limit,Instant createdFrom,Instant createdTo,Map<String,Object> filters) {
        this(after,limit,createdFrom,createdTo,filters,limit+1);
    }
    /** 查询对象不能绕过页大小校验，构造后条件不可变。 */
    public AdminQuery {
        if(after<0 || limit<1 || limit>100 || fetchLimit!=limit+1) throw invalid();
        filters=Map.copyOf(filters);
    }
    /** 重复参数拒绝，避免网关与后端对取首值或末值产生不同解释。 */
    public static AdminQuery parse(Map<String,List<String>> input,Map<String,Set<String>> allowed,boolean window) {
        return parse(input,allowed,window,null);
    }
    /** 创建时间和业务时间各自校验成对与范围，组合条件由所属领域仓储求交集。 */
    public static AdminQuery parse(Map<String,List<String>> input,Map<String,Set<String>> allowed,boolean window,WindowKeys businessWindow) {
        var filters=new HashMap<String,Object>(); long after=0;int limit=20;Instant from=null,to=null;
        for(var entry:input.entrySet()) {
            String key=entry.getKey();var values=entry.getValue();
            boolean businessTime=businessWindow!=null && businessWindow.contains(key);
            if((!allowed.containsKey(key) && !COMMON.contains(key) && !businessTime) || values.size()!=1) throw invalid();
            String value=values.getFirst();if(value==null || value.isBlank() || !value.equals(value.strip())) throw invalid();
            if(businessTime) {
                filters.put(key,time(value));
                continue;
            }
            switch(key) {
                case "after" -> after=number(value,true);
                case "limit" -> { long n=number(value,false);if(n>100) throw invalid();limit=(int)n; }
                case "createdFrom" -> { if(!window) throw invalid();from=time(value); }
                case "createdTo" -> { if(!window) throw invalid();to=time(value); }
                default -> {
                    Set<String> choices=allowed.get(key);
                    if(!choices.isEmpty() && !choices.contains(value)) throw invalid();
                    if(key.endsWith("Id")) filters.put(key,number(value,false));
                    else if(key.equals("abnormalOnly") || key.equals("leaseExpired")) {
                        if(!Set.of("true","false").contains(value)) throw invalid();filters.put(key,Boolean.valueOf(value));
                    } else { text(value,64);filters.put(key,value); }
                }
            }
        }
        validateWindow(from,to);
        if(businessWindow!=null) validateWindow((Instant)filters.get(businessWindow.from()),(Instant)filters.get(businessWindow.to()));
        if(Boolean.TRUE.equals(filters.get("abnormalOnly")) && "NORMAL".equals(filters.get("disposition"))) throw invalid();
        return new AdminQuery(after,limit,from,to,filters);
    }
    private static void validateWindow(Instant from,Instant to) {
        if((from==null)!=(to==null) || (from!=null && (!from.isBefore(to) || Duration.between(from,to).compareTo(MAX_WINDOW)>0))) throw invalid();
    }
    /** 路径数字ID与游标都限定Java long范围，不允许前导零或科学计数法。 */
    public static long number(String value,boolean zero) {
        if(value==null || !(zero?value.matches("0|[1-9][0-9]{0,18}"):value.matches("[1-9][0-9]{0,18}"))) throw invalid();
        try { return Long.parseLong(value); } catch(NumberFormatException ex) { throw invalid(); }
    }
    /** 业务单号采用精确匹配，不允许控制字符进入日志或SQL筛选。 */
    public static String text(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    private static Instant time(String value) {
        try { Instant at=Instant.parse(value);if(at.getNano()%1_000_000!=0 || at.isBefore(Instant.parse("1000-01-01T00:00:00Z")) || at.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))) throw invalid();return at; }
        catch(DateTimeException ex) { throw invalid(); }
    }
    /** 白名单查询值在Controller定义，公共解析不持有业务枚举。 */
    public static Map<String,Set<String>> fields(String... names) {
        var result=new HashMap<String,Set<String>>();for(String name:names) result.put(name,Set.of());return result;
    }
    private static TradeException invalid() { return new TradeException("INVALID_ARGUMENT","查询字段、编号、状态、时间窗口或分页参数无效"); }
}
