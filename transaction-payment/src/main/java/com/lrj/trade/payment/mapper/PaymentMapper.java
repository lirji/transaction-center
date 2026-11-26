package com.lrj.trade.payment.mapper;

import com.lrj.trade.payment.model.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 支付领域持久化边界，事务和业务状态判断由服务编排。 */
@Mapper
public interface PaymentMapper {
    /** 按租户主键范围最多扫描一页，不为稀疏异常资金做无界过滤扫描。 */
    List<CompensationCandidate> compensationCandidates(@Param("tenant") long tenant,@Param("after") long after,@Param("limit") int limit);
    /** 退款通过原尝试主键解析不可变渠道引用，禁止按当前商户配置重新路由。 */
    List<PaymentAttempt> findAttemptById(@Param("tenant") long tenant,@Param("attempt") long attempt);
    /** 升级收尾按不可变主键分页读取引用，不在运行时跨域扫描任务表。 */
    List<RecoveryCandidate> recoveryCandidates(@Param("tenant") long tenant,@Param("after") long after,@Param("limit") int limit);
    /** 支付意图与订单许可同事务，订单唯一防止换幂等键创建第二意图。 */
    int insertIntent(@Param("tenant") long tenant,@Param("number") String number,@Param("order") long order,@Param("member") long member,@Param("amount") long amount,@Param("currency") String currency,@Param("now") Instant now);
    /** 调用方已锁订单，随后锁意图形成固定锁序。 */
    List<PaymentIntent> lockByOrder(@Param("tenant") long tenant,@Param("order") long order);
    /** 本人查询显式限定会员和租户。 */
    /** 仅在发号碰撞后通过全局唯一索引判断冲突对象。 */
    String lockBusinessNumber(@Param("number") String number);

    List<PaymentView> findOwn(@Param("tenant") long tenant,@Param("member") long member,@Param("number") String number);
    /** 最后尝试早已关闭后才取消订单时，空活跃指针仍可安全结束意图。 */
    int finishWithoutActive(@Param("tenant") long tenant,@Param("intent") long intent,@Param("now") Instant now);
    /** 生成的渠道请求号永久保留，关闭后也不复用。 */
    int insertAttempt(@Param("tenant") long tenant,@Param("intent") long intent,@Param("order") long order,@Param("channel") long channel,@Param("number") String number,@Param("now") Instant now);
    /** 只有无未决尝试且未设关闭屏障时可建立新活跃指针。 */
    int setActive(@Param("tenant") long tenant,@Param("intent") long intent,@Param("number") String number,@Param("now") Instant now);
    /** 先读取不可变所属订单以获取正确锁序，不用此快照裁决可变状态。 */
    List<PaymentAttempt> findAttempt(@Param("tenant") long tenant,@Param("number") String number);
    /** 订单和意图锁之后当前读尝试，避免旧快照覆盖终态。 */
    List<PaymentAttempt> lockAttempt(@Param("tenant") long tenant,@Param("number") String number);
    /** 外部调用前持久化未知状态，提交后宕机只能同号查询，不换号重付。 */
    int markSubmitted(@Param("tenant") long tenant,@Param("number") String number,@Param("now") Instant now);
    /** 关闭屏障不可清除；不据此直接释放库存。 */
    int fenceIntent(@Param("tenant") long tenant,@Param("intent") long intent,@Param("now") Instant now);
    /** 真实交易号永久去重，与回调事件或HTTP命令保留期无关。 */
    List<PaymentReceipt> lockReceipt(@Param("tenant") long tenant,@Param("code") String code,@Param("channelMerchant") String channelMerchant,@Param("transaction") String transaction);
    /** 无论正常或异常均保存真实资金，不因订单已付丢掉第二笔钱。 */
    int insertReceipt(@Param("tenant") long tenant,@Param("receiptNo") String receiptNo,@Param("businessNo") String businessNo,@Param("intent") long intent,@Param("attempt") long attempt,@Param("order") long order,@Param("code") String code,@Param("channelMerchant") String channelMerchant,@Param("transaction") String transaction,@Param("amount") long amount,@Param("currency") String currency,@Param("providerPaid") Instant providerPaid,@Param("now") Instant now,@Param("disposition") String disposition,@Param("evidence") String evidence);
    /** 资金流水和原收款同事务，异常资金也需逐笔可对账。 */
    /** 实收已按渠道幂等键确认不存在时，仅此唯一号可触发重发。 */
    String lockReceiptBusinessNumber(@Param("number") String number);
    /** 正向和退款流水共享同一全局号空间。 */
    String lockLedgerBusinessNumber(@Param("number") String number);
    int insertLedger(@Param("tenant") long tenant,@Param("receipt") long receipt,@Param("number") String number,@Param("amount") long amount,@Param("currency") String currency,@Param("evidence") String evidence,@Param("now") Instant now);
    /** 首个正常收款绑定意图，禁止替换既有正常收款。 */
    int markSucceeded(@Param("tenant") long tenant,@Param("intent") long intent,@Param("receipt") long receipt,@Param("now") Instant now);
    /** 异常收款进入核对，已经正常成功的意图不回退。 */
    int markReview(@Param("tenant") long tenant,@Param("intent") long intent,@Param("now") Instant now);
    /** 真实资金不被晚到失败覆盖；已可靠关闭的矛盾成功保留原屏障。 */
    int markAttemptSucceeded(@Param("tenant") long tenant,@Param("number") String number,@Param("now") Instant now);
    /** 仅可信不可支付证据可推进关闭；已有成功资金不能被关单回退。 */
    int closeAttempt(@Param("tenant") long tenant,@Param("number") String number,@Param("evidence") String evidence,@Param("now") Instant now);
    /** 当前尝试可靠关闭后才清指针，过期或取消时关闭意图，否则允许新渠道尝试。 */
    int clearActive(@Param("tenant") long tenant,@Param("intent") long intent,@Param("number") String number,@Param("status") String status,@Param("fence") Instant fence,@Param("now") Instant now);
}
