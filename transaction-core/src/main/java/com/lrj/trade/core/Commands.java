package com.lrj.trade.core;

import java.util.function.Supplier;
import com.lrj.trade.core.mapper.CommandsMapper;
import com.lrj.trade.core.model.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 数据库唯一键串行化相同命令，业务结果和幂等记录在同一事务提交。 */
@Component
public class Commands {
    private final CommandsMapper mapper;
    private final TransactionTemplate tx;
    /** 使用同一数据源事务管理器，允许后续订单组合库存端口。 */
    public Commands(CommandsMapper mapper, PlatformTransactionManager manager) {
        this.mapper=mapper; this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(10);
    }
    /** 只读已提交成功结果；完整作用域和摘要不匹配时拒绝，最终写入仍须run串行裁决。 */
    public String completed(Actor actor,String operation,String key,String hash) {
        Checks.text(key,128);
        var row=mapper.findCommand(actor.tenantId(),actor.key(),operation,key);
        if(row==null) return null;
        if(!hash.equals(row.requestHash())) throw new TradeException("IDEMPOTENCY_CONFLICT","相同幂等键的请求内容不同");
        return row.resultRef();
    }
    /** 不在事务内等待外部系统；失败回滚命令，重试不会留下半个业务结果。 */
    public String run(Actor actor, String operation, String key, String hash, Supplier<String> action) {
        Checks.text(key, 128);
        return tx.execute(status -> {
            mapper.insertIfAbsent(actor.tenantId(),actor.key(),operation,key,hash);
            var row = mapper.lockCommand(actor.tenantId(),actor.key(),operation,key);
            if (!hash.equals(row.requestHash())) {
                throw new TradeException("IDEMPOTENCY_CONFLICT", "相同幂等键的请求内容不同");
            }
            if (row.resultRef() != null) return row.resultRef();
            String result = action.get();
            mapper.saveResult(result,actor.tenantId(),actor.key(),operation,key);
            return result;
        });
    }
}
