package com.lrj.trade.catalog;
import com.lrj.trade.core.TradeException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 展示回源单独使用主库短只读事务，不沿用外部资金事务或旧RR快照。 */
@Service
public class CatalogQueryLoader {
    private final CatalogService catalog;private final SkuService skus;
    /** 原领域查询继续承担已发布条件和租户过滤。 */
    public CatalogQueryLoader(CatalogService catalog,SkuService skus) {this.catalog=catalog;this.skus=skus;}
    /** 只有回源leader进入此事务，Redis与单飞等待在事务外。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true,timeout=2)
    public CatalogCachePort.Value load(CatalogCachePort.Query query) {
        return switch(query.kind()) {
            case PRODUCTS -> new CatalogCachePort.Value(catalog.list(query.tenant(),query.after(),query.limit(),false),List.of());
            case SKUS -> new CatalogCachePort.Value(List.of(),skus.list(query.tenant(),query.resource(),query.after(),query.limit(),false));
            case PRODUCT -> {try {yield new CatalogCachePort.Value(List.of(catalog.detail(query.tenant(),query.resource(),false)),List.of());}catch(TradeException error) {if(!error.code().equals("NOT_FOUND")) throw error;yield new CatalogCachePort.Value(List.of(),List.of());}}
        };
    }
}
