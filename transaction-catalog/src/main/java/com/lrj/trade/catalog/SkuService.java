package com.lrj.trade.catalog;

import org.springframework.transaction.annotation.Isolation;
import java.time.Instant;
import com.lrj.trade.core.query.AdminNumberedPage;
import com.lrj.trade.core.*;
import java.util.List;
import java.util.Map;
import com.lrj.trade.catalog.mapper.SkuMapper;
import com.lrj.trade.catalog.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 多SKU草稿服务；修改锁定商品版本，防止发布时产生一半新一半旧的快照。 */
@Service
public class SkuService {
    private final SkuMapper mapper;
    private final Commands commands;
    private final AuditWriter audit;
    /** 使用同一快照读取准确条数和页内记录，沿用当前租户与允许的业务筛选。 */
    @Transactional(readOnly = true, timeout = 3, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public AdminNumberedPage<Sku> numberedDraftSkus(long tenant, long product, int limit, long page) {
        Checks.positive(tenant);
        Checks.positive(product);
        return AdminNumberedPage.of(page, limit, mapper.listDraftSkusCount(tenant, product), Instant.now(),
                offset -> mapper.listDraftSkusAtOffset(tenant, product, limit, offset));
    }

    /** 所有SKU写操作与商品版本及审计同事务。 */
    public SkuService(SkuMapper mapper,Commands commands,AuditWriter audit) {
        this.mapper=mapper; this.commands=commands; this.audit=audit;
    }
    /** 给草稿添加SKU，已发布商品必须先下架再编辑，且每商品最多一百个SKU。 */
    public String add(Actor actor,String key,long product,long expectedVersion,String skuNo,long price,String currency,
                      String stockType,Map<String,String> specification) {
        Checks.text(skuNo,64); validate(price,currency,stockType); String spec=Specifications.encode(specification);
        return commands.run(actor,"sku.add",key,Checks.hash(product,expectedVersion,skuNo,price,currency,stockType,spec),()->{
            advanceDraft(actor.tenantId(),product,expectedVersion);
            if(mapper.countSkus(actor.tenantId(),product)>=100)
                throw new TradeException("INVALID_ARGUMENT","每商品最多一百个SKU");
            mapper.insertSku(actor.tenantId(),product,skuNo,price,currency,stockType,spec);
            String id=mapper.findSkuId(actor.tenantId(),skuNo);
            audit.append(actor,"sku.add","SKU",id,"新增商品规格",expectedVersion,expectedVersion+1);
            return id;
        });
    }
    /** 仅修改指定SKU，不把某个规格的价格写到同商品的所有规格上。 */
    public String edit(Actor actor,String key,long product,long sku,long expectedVersion,long price,String currency,
                       String stockType,Map<String,String> specification) {
        Checks.positive(sku); validate(price,currency,stockType); String spec=Specifications.encode(specification);
        return commands.run(actor,"sku.edit",key,Checks.hash(product,sku,expectedVersion,price,currency,stockType,spec),()->{
            advanceDraft(actor.tenantId(),product,expectedVersion);
            if(mapper.updateSku(price,currency,stockType,spec,actor.tenantId(),product,sku)!=1) throw new TradeException("NOT_FOUND","SKU不存在");
            audit.append(actor,"sku.edit","SKU",Long.toString(sku),"修改指定SKU草稿",expectedVersion,expectedVersion+1);
            return Long.toString(sku);
        });
    }
    /** 管理列表读取草稿，会员列表只读取当前发布版本；未发布SKU不会泄露给会员。 */
    public List<Sku> list(long tenant,long product,long after,int limit,boolean admin) {
        Checks.positive(tenant); Checks.positive(product);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","分页参数无效");

        return (admin ? mapper.listDraftSkus(tenant,product,after,limit) : mapper.listPublishedSkus(tenant,product,after,limit));
    }
    /** 报价锁定当前发布商品并读取不可变版本，使用当前读避免等待发布后读到旧事务快照。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Sku published(long tenant,long sku) {
        Checks.positive(tenant); Checks.positive(sku);
        var products=mapper.findProductIds(tenant,sku);
        if(products.isEmpty()) throw new TradeException("NOT_FOUND","SKU不存在或不可售");
        var revisions=mapper.lockPublishedRevision(tenant,products.getFirst());
        if(revisions.isEmpty()) throw new TradeException("NOT_FOUND","SKU不存在或不可售");
        return mapper.lockPublishedSku(tenant,sku,revisions.getFirst())
            .stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","SKU发布版本不存在"));
    }
    private void advanceDraft(long tenant,long product,long expected) {
        Checks.positive(product);
        if(expected<0) throw new TradeException("INVALID_ARGUMENT","版本不能为负");
        if(mapper.advanceDraftVersion(tenant,product,expected)!=1)
            throw mutationFailure(tenant,product,expected);
    }
    private void validate(long price,String currency,String type) {
        if(price<0 || price>9007199254740991L || currency==null || !currency.matches("[A-Z]{3}")
            || !("PHYSICAL".equals(type)||"SERVICE".equals(type))) throw new TradeException("INVALID_ARGUMENT","SKU金额、币种或库存类型无效");
    }

    /** 成功与原命令重放不增查询；仅失败后在原事务中当前锁读分类。 */
    private TradeException mutationFailure(long tenant,long product,long expected) {
        var versions=mapper.lockMutationVersion(tenant,product);
        if(versions.isEmpty()) return new TradeException("NOT_FOUND","商品不存在");
        if(versions.getFirst()!=expected) return new TradeException("VERSION_CONFLICT","商品版本已更新");
        return new TradeException("STATE_CONFLICT","当前商品状态不允许此操作");
    }
}
