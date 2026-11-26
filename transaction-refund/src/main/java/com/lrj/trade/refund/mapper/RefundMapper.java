package com.lrj.trade.refund.mapper;
import com.lrj.trade.refund.model.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 退款应用自己的持久化接口，所有SQL位于XML且显式限定租户。 */
@Mapper
public interface RefundMapper {
    /** 使用租户/状态/主键复合索引有界查询，禁止跨租户后台扫描。 */
    List<RefundQueueItem> listQueue(@Param("tenant") long tenant,@Param("status") String status,@Param("after") long after,@Param("limit") int limit);
    /** 当前已提交非终态申请按主键分页，用于滚动升级收尾补漏。 */
    List<RefundRow> recoveryCandidates(@Param("tenant") long tenant,@Param("after") long after,@Param("limit") int limit);
    /** 原退款号定位不可变申请，证据接收不允许重新绑定资金来源。 */
    List<RefundRow> findByMerchantRefund(@Param("tenant") long tenant,@Param("number") String number);
    /** 到期且未派发申请独立结束，不制造不存在的外部拒绝审批事实。 */
    int markExpired(@Param("tenant") long tenant,@Param("number") String number,@Param("version") long version,@Param("now") Instant now);
    /** 读取租户当前政策快照，不锁外部网络。 */
    List<RefundPolicy> findPolicy(@Param("tenant") long tenant);
    /** 配置变更前当前读策略版本。 */
    List<RefundPolicy> lockPolicy(@Param("tenant") long tenant);
    /** 先占唯一键再当前读，不能覆盖已配置的政策。 */
    int initializePolicy(@Param("tenant") long tenant,@Param("currency") String currency,@Param("now") Instant now);
    /** 按版本更新并由服务同事务审计。 */
    int updatePolicy(@Param("tenant") long tenant,@Param("currency") String currency,@Param("limit") long limit,@Param("version") long version,@Param("now") Instant now);
    /** 来源锁之后按永久异常收款关联当前读，不依赖创建时系统身份。 */
    List<RefundRow> lockCompensation(@Param("tenant") long tenant,@Param("receipt") long receipt);
    /** 永久主体请求号去重，新插入尚不可见，资金/明细随后在同事务建立。 */
    int insertRefund(@Param("tenant") long tenant,@Param("number") String number,@Param("businessNo") String businessNo,@Param("request") String request,@Param("actor") String actor,@Param("member") long member,@Param("order") long order,@Param("orderNo") String orderNo,@Param("receipt") long receipt,@Param("receiptNo") String receiptNo,@Param("merchantRefund") String merchantRefund,@Param("amount") long amount,@Param("currency") String currency,@Param("reason") String reason,@Param("hash") String hash,@Param("status") String status,@Param("policyVersion") long policyVersion,@Param("policyLimit") long policyLimit,@Param("approval") String approval,@Param("deadline") Instant deadline,@Param("now") Instant now,@Param("compensation") Long compensation);
    /** 永久请求键占位后精确当前读，重放返回原聚合。 */
    /** 申请来源键用于当前读，重复请求必须返回已持久化退款。 */
    List<RefundRow> lockByRequest(@Param("tenant") long tenant,@Param("actor") String actor,@Param("request") String request);
    /** 只读归属或展示，不据此作状态转换。 */
    /** 会员展示号解析使用独立唯一索引，原流程继续锁原单号。 */
    List<RefundRow> findRefundByBusinessNumber(@Param("tenant") long tenant,@Param("number") String number);
    /** 原退款请求号在确认和重放时保持有效。 */
    List<RefundRow> findRefund(@Param("tenant") long tenant,@Param("number") String number);
    /** 在订单/资金资源锁之后精确锁定现有退款聚合。 */
    List<RefundRow> lockRefund(@Param("tenant") long tenant,@Param("number") String number);
    /** 按规范化组件键读取固定归属，确定性更新额度。 */
    List<RefundItemRow> items(@Param("tenant") long tenant,@Param("refund") long refund);
    /** 额度上限来自冻结订单，重复初始化不覆盖累计退款。 */
    int initializeBudget(@Param("tenant") long tenant,@Param("order") long order,@Param("key") String key,@Param("type") String type,@Param("item") Long item,@Param("limit") long limit,@Param("now") Instant now);
    /** 组件额度必须与冻结上限相同，条件更新防超退。 */
    int reserveBudget(@Param("tenant") long tenant,@Param("order") long order,@Param("key") String key,@Param("limit") long limit,@Param("amount") long amount,@Param("now") Instant now);
    /** 固定每笔退款金额归属，不能事后换商品或组件。 */
    int insertItem(@Param("tenant") long tenant,@Param("refund") long refund,@Param("order") long order,@Param("key") String key,@Param("type") String type,@Param("item") Long item,@Param("quantity") long quantity,@Param("amount") long amount,@Param("now") Instant now);
    /** 可靠释放与资金Port、应用终态同事务。 */
    int releaseBudget(@Param("tenant") long tenant,@Param("order") long order,@Param("key") String key,@Param("amount") long amount,@Param("now") Instant now);
    /** 成功原子转移组件预占到累计已退。 */
    int confirmBudget(@Param("tenant") long tenant,@Param("order") long order,@Param("key") String key,@Param("amount") long amount,@Param("now") Instant now);
    /** 仅待审批且版本匹配可受理唯一决定，不允许覆盖已执行申请。 */
    int decide(@Param("tenant") long tenant,@Param("number") String number,@Param("version") long version,@Param("status") String status,@Param("decisionVersion") long decisionVersion,@Param("actor") String actor,@Param("decision") String decision,@Param("evidence") String evidence,@Param("now") Instant now);
    /** 只有免审或已批准的就绪申请可获得本地派发屏障。 */
    int markSubmitted(@Param("tenant") long tenant,@Param("number") String number,@Param("version") long version,@Param("now") Instant now);
    /** 实际成功当前读裁决，必须和资金账本及组件余额一起提交。 */
    int markSucceeded(@Param("tenant") long tenant,@Param("number") String number,@Param("version") long version,@Param("provider") String provider,@Param("now") Instant now);
    /** 可靠不可再成功结果才允许结束已派发申请并释放额度。 */
    int markFailed(@Param("tenant") long tenant,@Param("number") String number,@Param("version") long version,@Param("now") Instant now);
}
