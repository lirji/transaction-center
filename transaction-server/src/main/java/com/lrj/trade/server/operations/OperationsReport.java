package com.lrj.trade.server.operations;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lrj.trade.core.query.AdminPage;
import java.math.BigDecimal;
import java.time.Instant;

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

    /** 以账本主键推进明细，真实业务号用于打开订单、退款和实收详情。 */
    public record Fact(@JsonIgnore String cursor, String ledgerNo, String businessNo, String orderNo, String receiptNo,
                       String refundNo, Scope kind, long amount, String currency, Instant confirmedAt) implements AdminPage.Row {}
}
