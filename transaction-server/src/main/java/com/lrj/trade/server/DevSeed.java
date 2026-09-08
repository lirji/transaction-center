package com.lrj.trade.server;

import com.lrj.trade.core.Actor;
import com.lrj.trade.member.MemberService;
import com.lrj.trade.catalog.CatalogService;
import com.lrj.trade.inventory.InventoryService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import com.lrj.trade.server.mapper.SeedDatabaseMapper;
import org.springframework.stereotype.Component;

/** 可重复开发种子入口；通过业务服务写库，前端不持有硬编码Mock数据。 */
@Component
@Profile("dev-seed")
public class DevSeed implements ApplicationRunner {
    private final SeedDatabaseMapper mapper;
    private final MemberService members;
    private final CatalogService catalog;
    private final InventoryService inventory;
    /** 复用正式业务端口，种子库存同样产生流水与待发布事实。 */
    public DevSeed(SeedDatabaseMapper mapper,MemberService members,CatalogService catalog,InventoryService inventory) {
        this.mapper=mapper; this.members=members; this.catalog=catalog; this.inventory=inventory;
    }
    /** 只有显式dev-seed配置且目标库以_dev结尾时才写入，避免污染正式业务库。 */
    @Override
    public void run(ApplicationArguments args) {
        String database=mapper.currentDatabase();
        if(database==null || !database.equals("transaction_center_dev"))
            throw new IllegalStateException("开发种子只允许写入transaction_center_dev库");
        Actor actor=new Actor(900001,"urn:transaction-center:dev-seed","seed-v1");
        members.register(actor,"演示会员");
        String product=catalog.create(actor,"seed-product-v1","DEMO-P001","演示商品","DEMO-S001",9900,"CNY");
        catalog.publish(actor,"seed-publish-v1",Long.parseLong(product),0);
        long sku=Long.parseLong(catalog.list(actor.tenantId(),0,100,true).stream()
            .filter(p->p.productId().equals(product)).findFirst().orElseThrow().skuId());
        long warehouse=Long.parseLong(inventory.warehouse(actor,"seed-warehouse-v1","DEMO-W001","演示仓库"));
        inventory.adjust(actor,"seed-stock-v1",warehouse,sku,100,"开发环境初始库存");
    }
}
