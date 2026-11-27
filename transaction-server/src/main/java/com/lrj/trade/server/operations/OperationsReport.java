package com.lrj.trade.server.operations;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lrj.trade.core.query.AdminPage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 经营投影来自交易本地事实，对外不返回渠道账号与验签材料。 */
public final class OperationsReport {
    private OperationsReport() {}

    /** 资金方向和原实收处置决定口径；NET 作为明细查询范围，永不写入资金事实。 */
    public enum Scope { NORMAL_RECEIPT, ABNORMAL_RECEIPT, NORMAL_REFUND, ABNORMAL_REFUND, NET }

    /** 以十进制值接收库内汇总，避免合法的逐笔整数在相加后溢出。 */
    public record Totals(long orderCount, BigDecimal orderPayable, long paidOrderCount,
                         BigDecimal normalReceipt, BigDecimal abnormalReceipt,
                         BigDecimal normalRefund, BigDecimal abnormalRefund) {}

    /** 总额最小币种单位使用十进制整数字符串，前端可无损读取大额汇总。 */
    public record Summary(Instant from, Instant to, Instant observedAt, String currency,
                          long orderCount, String orderPayable, long paidOrderCount,
                          String normalReceipt, String abnormalReceipt,
                          String normalRefund, String abnormalRefund, String netReceipt) {
        /** 期间净收只能扣除用于计算正常收款的同类资金流出。 */
        static Summary of(Instant from, Instant to, Instant observedAt, String currency, Totals totals) {
            return new Summary(from,to,observedAt,currency,totals.orderCount(),minor(totals.orderPayable()),
                totals.paidOrderCount(),minor(totals.normalReceipt()),minor(totals.abnormalReceipt()),
                minor(totals.normalRefund()),minor(totals.abnormalRefund()),
                minor(totals.normalReceipt().subtract(totals.normalRefund())));
        }

        private static String minor(BigDecimal amount) { return amount.toBigIntegerExact().toString(); }
    }

    /** 订单状态是观察时快照，两个转化计数都来自同一批创建订单。 */
    public record OrderState(String status, long count, long payableCount, long convertedCount) {}

    /** 订单按创建日，资金按本地确认日，对外金额以最小币种单位返回。 */
    public record Daily(LocalDate day, long orderCount, long paidOrderCount, String normalReceipt,
                        String normalRefund, String netReceipt) {}

    /** 仓储使用十进制接收聚合值，避免在聚合时丢失资金精度。 */
    public record DailyTotals(LocalDate day, long orderCount, long paidOrderCount, BigDecimal normalReceipt,
                              BigDecimal normalRefund) {
        Daily toView() {
            return new Daily(day, orderCount, paidOrderCount, Summary.minor(normalReceipt), Summary.minor(normalRefund),
                    Summary.minor(normalReceipt.subtract(normalRefund)));
        }
    }

    /** 开闭窗口沿用原经营汇总，金额只能在该单币种范围内比较。 */
    public record Analysis(Instant from, Instant to, Instant observedAt, String currency,
                           long payableCount, long convertedCount, List<OrderState> orderStates, List<Daily> daily) {}

    /** 只包含结算时已持久化的关联，不表示来源系统预算消耗或权益发放。 */
    public record LinkedCheckout(String orderNo, String orderBusinessNo, String commerceTenant, String storeId,
                                 String commerceQuoteId, String fundingApplicationId, Instant createdAt) {}

    /** 当前交易查询范围内的关联条数与预算回执绑定条数。 */
    public record LinkedTotals(long linkedCount, long fundingCount, BigDecimal discount) {}

    /** 关联明细附带整体计数及交易确认的冻结优惠金额。 */
    public record LinkedCheckouts(com.lrj.trade.core.query.AdminNumberedPage<LinkedCheckout> rows,
                                  long fundingCount, String frozenDiscount) {}

    /** 以账本主键推进明细，真实业务号用于打开订单、退款和实收详情。 */
    public record Fact(@JsonIgnore String cursor, String ledgerNo, String businessNo, String orderNo, String receiptNo,
                       String refundNo, String orderBusinessNo, String receiptBusinessNo, String refundBusinessNo,
                       Scope kind, long amount, String currency, Instant confirmedAt) implements AdminPage.Row {}
}
