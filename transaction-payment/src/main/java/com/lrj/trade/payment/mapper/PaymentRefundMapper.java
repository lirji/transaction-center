package com.lrj.trade.payment.mapper;

import com.lrj.trade.payment.model.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 支付域退款资金持久化，所有入口按租户绑定，业务锁序与证明语义由服务控制。 */
@Mapper
public interface PaymentRefundMapper {
    /** 已锁订单和意图后，按正常成功指针精确当前读。 */
    List<RefundSource> lockSourceById(@Param("tenant") long tenant,@Param("receipt") long receipt);
    /** 只取不可变归属以确定先锁订单，不据此判断剩余额度。 */
    List<RefundSource> findSource(@Param("tenant") long tenant,@Param("number") String number);
    /** 已锁订单与意图后当前读真实收款。 */
    List<RefundSource> lockSource(@Param("tenant") long tenant,@Param("number") String number);
    /** 只取预占不可变来源，再按固定锁序进入状态处理。 */
    List<RefundHold> findHold(@Param("tenant") long tenant,@Param("refund") String refund);
    /** 预占存在后才精确锁定，避免先锁缺失唯一键的间隙。 */
    List<RefundHold> lockHold(@Param("tenant") long tenant,@Param("refund") String refund);
    /** 永久退款号占位不覆盖已有内容，以新reservation号识别插入。 */
    int insertHold(@Param("tenant") long tenant,@Param("reservation") String reservation,@Param("refund") String refund,@Param("receipt") long receipt,@Param("receiptNo") String receiptNo,@Param("order") long order,@Param("amount") long amount,@Param("currency") String currency,@Param("channel") String channel,@Param("merchant") String merchant,@Param("now") Instant now);
    /** 条件更新保证成功加预占不超过原实收。 */
    int reserveAmount(@Param("tenant") long tenant,@Param("receipt") long receipt,@Param("amount") long amount,@Param("currency") String currency,@Param("now") Instant now);
    /** 先写派发屏障再由外层fencing提交，未知结果不能清屏障。 */
    int markSubmitted(@Param("tenant") long tenant,@Param("refund") String refund,@Param("now") Instant now);
    /** 只有原预占足够时释放，本方法不能独立代表渠道退款失败。 */
    int releaseAmount(@Param("tenant") long tenant,@Param("receipt") long receipt,@Param("amount") long amount,@Param("now") Instant now);
    /** 释放与收款余额同事务，保留来源和永久退款号。 */
    int releaseHold(@Param("tenant") long tenant,@Param("refund") String refund,@Param("expected") String expected,@Param("evidence") String evidence,@Param("now") Instant now);
    /** 预占转成功，保持二者和不变且不清除会员首付历史。 */
    int confirmAmount(@Param("tenant") long tenant,@Param("receipt") long receipt,@Param("amount") long amount,@Param("now") Instant now);
    /** 可信成功只能从有额度状态确认，渠道退款号全局按收款主体去重。 */
    int confirmHold(@Param("tenant") long tenant,@Param("refund") String refund,@Param("provider") String provider,@Param("occurred") Instant occurred,@Param("evidence") String evidence,@Param("now") Instant now);
    /** 追加独立退款流水，不能覆盖原收款账本。 */
    int insertLedger(@Param("tenant") long tenant,@Param("receipt") long receipt,@Param("refund") String refund,@Param("reservation") String reservation,@Param("number") String number,@Param("amount") long amount,@Param("currency") String currency,@Param("evidence") String evidence,@Param("now") Instant now);
}
