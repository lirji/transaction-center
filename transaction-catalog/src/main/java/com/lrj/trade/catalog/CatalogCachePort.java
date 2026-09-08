package com.lrj.trade.catalog;
import com.lrj.trade.catalog.model.*;
import java.util.*;
/** 展示缓存不参与报价、库存或资金判断；Redis实现留在server适配层。 */
public interface CatalogCachePort {
    /** 读取当前代次及对应展示结果，不可用时必须显式返回降级分类。 */
    Probe lookup(Query query);
    /** 只写最初读取的代次，旧回源永远不能污染后来的代次。 */
    void store(Query query,String generation,Value value);
    /** 在发布事务内注册提交后异步失效，不在交易线程发送Redis网络。 */
    void invalidateAfterCommit(long tenant);
    /** 有限展示查询类型，不允许任意键和无界列表。 */
    enum Kind { PRODUCT, PRODUCTS, SKUS }
    /** 规范查询身份，租户、资源和分页同时纳入代次键。 */
    record Query(long tenant,Kind kind,long resource,long after,int limit) {
        /** 参数无效时不能触发Redis或主库回源。 */
        public Query {if(tenant<1 || kind==null || resource<0 || after<0 || limit<1 || limit>100 || kind!=Kind.PRODUCTS && resource<1) throw new com.lrj.trade.core.TradeException("INVALID_ARGUMENT","商品展示查询参数无效");}
        /** 固定字段摘要防止不同游标或租户复用缓存。 */
        public String identity() {return com.lrj.trade.core.Checks.hash(tenant,kind,resource,after,limit);}
        /** 缓存不能混入管理员草稿、其他商品SKU或超出请求范围的记录。 */
        public void validate(Value value) {
            if(value==null || value.products().size()>limit || value.skus().size()>limit || kind==Kind.SKUS && !value.products().isEmpty() || kind!=Kind.SKUS && !value.skus().isEmpty()) throw new IllegalArgumentException("缓存查询形状无效");
            long previous=after;
            for(Product p:value.products()) {long id=identifier(p.productId());identifier(p.skuId());text(p.name(),200);currency(p.currency());if(!"PUBLISHED".equals(p.status()) || p.publishedRevision()==null || p.publishedRevision()<1 || p.version()<p.publishedRevision() || p.unitPrice()<0 || p.unitPrice()>9007199254740991L || kind==Kind.PRODUCT && id!=resource || kind==Kind.PRODUCTS && id<=previous) throw new IllegalArgumentException("缓存商品范围无效");previous=id;}
            previous=after;for(Sku sku:value.skus()) {long id=identifier(sku.skuId());identifier(sku.productId());text(sku.skuNo(),64);text(sku.title(),200);currency(sku.currency());if(!Set.of("PHYSICAL","SERVICE").contains(sku.stockType()) || sku.specification().size()>16) throw new IllegalArgumentException("缓存规格无效");sku.specification().forEach((key,item)->{text(key,64);text(item,128);});if(!sku.productId().equals(Long.toString(resource)) || id<=previous || sku.revision()<1 || sku.unitPrice()<0 || sku.unitPrice()>9007199254740991L) throw new IllegalArgumentException("缓存SKU范围无效");previous=id;}
        }
        private static long identifier(String value) {if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("缓存ID无效");return Long.parseLong(value);}
        private static void text(String value,int max) {if(value==null || value.isBlank() || value.length()>max) throw new IllegalArgumentException("缓存必填文本无效");}
        private static void currency(String value) {if(value==null || !value.matches("[A-Z]{3}")) throw new IllegalArgumentException("缓存币种无效");}
    }
    /** 列表和SKU规格防御性复制，不把可变缓存对象交给请求调用方。 */
    record Value(List<Product> products,List<Sku> skus) {
        /** 序列化与回源均复用不可变值，负缓存使用两个空列表。 */
        public Value {products=List.copyOf(products);skus=skus.stream().map(s->new Sku(s.skuId(),s.skuNo(),s.productId(),s.title(),s.unitPrice(),s.currency(),s.stockType(),Map.copyOf(s.specification()),s.revision())).toList();}
        /** 不存在详情及空页采用更短TTL，避免穿透又不长期隐藏新发布数据。 */
        public boolean empty() {return products.isEmpty() && skus.isEmpty();}
    }
    /** 命中/缺失/不可用分开，未返回代次时只能限量主库降级。 */
    record Probe(String generation,Value value,boolean available) {}
}
