package com.lrj.trade.refund.mapper;

import com.lrj.trade.refund.model.RefundCallbackRecord;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 回调接收独立持久化边界，SQL与加密原文均不得进入业务日志。 */
@Mapper
public interface RefundInboxMapper {
    /** 事件键去重只保留首个不可变报文，不覆盖历史密文。 */
    int insert(@Param("tenant") long tenant, @Param("number") String number, @Param("refund") String refund, @Param("code") String code, @Param("merchant") String merchant, @Param("event") String event, @Param("request") String request, @Param("kind") String kind, @Param("transaction") String transaction, @Param("providerRefund") String providerRefund, @Param("amount") Long amount, @Param("currency") String currency, @Param("paid") Instant paid, @Param("payloadHash") byte[] payloadHash, @Param("factHash") String factHash, @Param("cipher") byte[] cipher, @Param("keyVersion") String keyVersion, @Param("signature") String signature,@Param("businessNo") String businessNo,@Param("now") Instant now);
    /** 插入后当前读识别重放与事实冲突，避免先锁不存在的事件键。 */
    List<RefundCallbackRecord> lockEvent(@Param("tenant") long tenant,@Param("code") String code,@Param("merchant") String merchant,@Param("event") String event);
    /** 按租户读取不可变证据，不能先锁回调再反向锁订单。 */
    List<RefundCallbackRecord> find(@Param("tenant") long tenant,@Param("number") String number);
    /** 资金处理成功后标记完成，必须参与外层业务事务。 */
    int processed(@Param("tenant") long tenant,@Param("number") String number);
    /** 确定异常保留证据并转人工核对，不能覆盖已处理状态。 */
    int review(@Param("tenant") long tenant,@Param("number") String number,@Param("error") String error);
}
