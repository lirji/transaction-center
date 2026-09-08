package com.lrj.trade.refund.model;
import java.time.Instant;

/** 不可变退款金额归属，数量不是实物退货入库许可。 */
public record RefundItemRow(String componentKey,String componentType,Long orderItemId,long quantity,long amount) {
    /** 日志不默认展开退款标识、原因或身份摘要。 */
    @Override public String toString() { return "RefundItemRow[REDACTED]"; }
}
