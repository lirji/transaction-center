package com.lrj.trade.catalog;

import com.lrj.trade.core.query.AdminQuery;
import com.lrj.trade.core.query.AdminNumberedPage;
import java.time.Instant;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.lrj.trade.core.*;
import java.util.List;
import com.lrj.trade.catalog.mapper.CatalogMapper;
import com.lrj.trade.catalog.model.*;
import org.springframework.stereotype.Service;

/** 商品草稿与不可变发布快照分离，后续订单能够引用稳定价格版本。 */
@Service
public class CatalogService implements CatalogPort {
    private final CatalogMapper mapper;
    private final Commands commands;
    private final CatalogCachePort cache;
    private final Outbox outbox;
    /** 使用同一只读快照读取总量与指定页，并复用列表投影。 */
    @Transactional(readOnly = true, timeout = 3, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public AdminNumberedPage<AdminProduct> numberedPage(long tenant, AdminQuery query, long page) {
        Checks.positive(tenant);
        var at = Instant.now();
        return AdminNumberedPage.of(page, query.limit(), mapper.listAdminProductsCount(tenant, query), at,
                offset -> mapper.listAdminProductsAtOffset(tenant, query, offset));
    }

    /** 管理端按已校验条件读取当前租户数据，保留原交易入口查询。 */
    public List<AdminProduct> adminList(long tenant,AdminQuery query) {
        Checks.positive(tenant);
        return mapper.listAdminProducts(tenant,query);
    }

    /** 商品图是运营元数据，保存 URL 不触发服务端远程图片请求。 */
    public long setImage(Actor actor, String key, long product, long version, String imageUrl) {
        Checks.positive(product);
        if (version < 0) throw AdminQuery.invalid();
        if (imageUrl != null) {
            Checks.text(imageUrl, 1024);
            try {
                var uri = java.net.URI.create(imageUrl);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getFragment() != null) throw AdminQuery.invalid();
            } catch (IllegalArgumentException badUrl) {
                throw AdminQuery.invalid();
            }
        }
        return Long.parseLong(commands.run(actor, "product.image.set", key, Checks.hash(product, version, imageUrl), () -> {
            if (mapper.setImage(actor.tenantId(), product, version, imageUrl) != 1)
                throw mutationFailure(actor.tenantId(), product, version);
            return Long.toString(version + 1);
        }));
    }

    /** 商品写命令统一经过事务幂等执行器。 */
    public CatalogService(CatalogMapper mapper, Commands commands, Outbox outbox,CatalogCachePort cache) {
        this.mapper=mapper; this.commands=commands; this.outbox=outbox; this.cache=cache;
    }
    /** 建立单SKU商品草稿；首批不伪装支持尚未实现的复杂规格组合。 */
    public String create(Actor actor, String key, String productNo, String name, String skuNo, long price, String currency) {
        Checks.text(productNo,64); Checks.text(name,200); Checks.text(skuNo,64); validatePrice(price,currency);
        return commands.run(actor,"product.create",key,Checks.hash(productNo,name,skuNo,price,currency),()->{
            mapper.insertProduct(actor.tenantId(),productNo,name);
            long id=mapper.findProductId(actor.tenantId(),productNo);
            mapper.insertPrimarySku(actor.tenantId(),id,skuNo,price,currency);
            return Long.toString(id);
        });
    }
    /** 修改必须匹配版本；已上架商品先下架，避免草稿直接改变线上价格。 */
    public String edit(Actor actor,String key,long product,long version,String name,long price,String currency) {
        Checks.text(name,200); validatePrice(price,currency);
        return commands.run(actor,"product.edit",key,Checks.hash(product,version,name,price,currency),()->{
            int changed=mapper.updateDraftName(name,actor.tenantId(),product,version);
            if(changed!=1) throw mutationFailure(actor.tenantId(),product,version);
            // 兼容原单SKU入口，仅更新最早创建的主SKU；其他规格必须使用明确的SKU编辑接口。
            mapper.updatePrimarySkuPrice(price,currency,actor.tenantId(),product);
            return Long.toString(product);
        });
    }
    /** 发布与快照写入同事务；不会覆盖此前发布过的SKU价格。 */
    public String publish(Actor actor,String key,long product,long version) {
        return commands.run(actor,"product.publish",key,Checks.hash(product,version),()->{
            int changed=mapper.publishProduct(actor.tenantId(),product,version);
            if(changed!=1) throw mutationFailure(actor.tenantId(),product,version);
            mapper.insertPublishedSnapshots(actor.tenantId(),product);
            cache.invalidateAfterCommit(actor.tenantId());
            outbox.append(actor.tenantId(),"product.published.internal.v0",Long.toString(product));
            return Long.toString(product);
        });
    }
    /** 下架不删除历史快照，已成立订单仍能解释其价格。 */
    public String unpublish(Actor actor,String key,long product,long version) {
        return commands.run(actor,"product.unpublish",key,Checks.hash(product,version),()->{
            if(mapper.unpublishProduct(actor.tenantId(),product,version)!=1) throw mutationFailure(actor.tenantId(),product,version);
            cache.invalidateAfterCommit(actor.tenantId());
            return Long.toString(product);
        });
    }
    /** 游标查询最多100条，避免无界查询耗尽数据库连接。 */
    public List<Product> list(long tenant,long after,int limit,boolean admin) {
        Checks.positive(tenant);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","分页参数无效");
        return mapper.listProducts(tenant,after,admin,limit);
    }
    /** 精确查询商品详情；会员请求下架或其他租户商品统一按不存在处理。 */
    public Product detail(long tenant,long product,boolean admin) {
        Checks.positive(tenant); Checks.positive(product);
        return mapper.findProduct(tenant,product,admin)
            .stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","商品不存在或不可售"));
    }
    /** 只检查本租户SKU；数据库FK另行阻止不存在的库存关联。 */
    @Override
    public void requireSku(long tenant,long sku,boolean published) {
        Integer count=mapper.countEligibleSku(tenant,sku,published);
        if(count==null || count!=1) throw new TradeException("NOT_FOUND","SKU不存在或不可售");
    }
    private void validatePrice(long price,String currency) {
        if(price<0 || price>9007199254740991L || currency==null || !currency.matches("[A-Z]{3}"))
            throw new TradeException("INVALID_ARGUMENT","金额或币种不合法");
    }

    /** 成功与原命令重放不增查询；仅失败后在原事务中当前锁读分类。 */
    private TradeException mutationFailure(long tenant,long product,long expected) {
        var versions=mapper.lockMutationVersion(tenant,product);
        if(versions.isEmpty()) return new TradeException("NOT_FOUND","商品不存在");
        if(versions.getFirst()!=expected) return new TradeException("VERSION_CONFLICT","商品版本已更新");
        return new TradeException("STATE_CONFLICT","当前商品状态不允许此操作");
    }
}
