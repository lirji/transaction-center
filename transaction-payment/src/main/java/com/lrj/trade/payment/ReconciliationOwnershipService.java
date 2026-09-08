package com.lrj.trade.payment;
import com.lrj.trade.core.*;
import com.lrj.trade.payment.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 报告归属核验加入受理事务，只读历史绑定而不锁定或派发资金。 */
@Service
public class ReconciliationOwnershipService implements ReconciliationOwnershipPort {
    private final ReconciliationOwnershipMapper mapper;private final ChannelMapper channels;
    /** 仅持久化读取边界，不依赖退款执行服务。 */
    public ReconciliationOwnershipService(ReconciliationOwnershipMapper mapper,ChannelMapper channels) {this.mapper=mapper;this.channels=channels;}
    /** 冻结绑定可以继续核对，跨租户或错误原渠道拒绝。 */
    @Override @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public void require(long tenant,long channel,String type,String reference) {
        if(channels.findById(tenant,channel).size()!=1) throw new TradeException("NOT_FOUND","渠道绑定不存在");
        if(type==null && reference==null) return;
        var ids=switch(type==null?"":type) {
            case "PAYMENT_ATTEMPT" -> mapper.attempt(tenant,reference);
            case "RECEIPT" -> mapper.receipt(tenant,reference);
            case "REFUND" -> mapper.refund(tenant,reference);
            default -> throw new TradeException("INVALID_ARGUMENT","差异业务引用类型无效");
        };
        if(ids.size()!=1 || ids.getFirst()!=channel) throw new TradeException("RECONCILIATION_BINDING_CONFLICT","差异引用与原渠道不一致");
    }
}
