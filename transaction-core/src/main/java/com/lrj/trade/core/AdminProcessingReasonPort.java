package com.lrj.trade.core;
/** 读取持久恢复任务的脱敏分类，不从退款状态猜测失败原因。 */
public interface AdminProcessingReasonPort {
    /** 缺少可靠分类时返回null，不返回渠道报文或自由文本。 */
    String refundReason(long tenant,String refundNo);
}
