package com.lrj.trade.member;

import com.lrj.trade.member.model.PaidOrderFact;
import com.lrj.trade.member.model.TradeFact;

/** 仅供可信收款编排调用的内部端口，不是用户可提交支付事实的接口。 */
public interface MemberTradeFactPort {
    /**
     * 调用方须先验证真实收款、足额同币种及订单归属，锁定订单并在同一事务提交正常收款、
     * 订单转换、库存确认和资金账本。异常或多付收款不得调用此方法；零元订单明确拒绝。
     * 不接收渠道时间作为首单排序依据，退款处理不能反向修改此事实。
     */
    PaidOrderFact confirmPaidOrder(long tenant,long member,long order,long amount,String currency);
    /** 从交易主库读取租户内消费快照；调用者负责可信身份及业务查询权限。 */
    TradeFact read(long tenant,long member);
}
