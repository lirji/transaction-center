package com.lrj.trade.payment;

/** 向退款等域公开原尝试渠道绑定；不暴露Mapper、回跳地址或密钥内容。 */
public interface PaymentChannelBindingPort {
    /** 读取历史不可变绑定，不以当前启停状态更换原资金路径；不是资金派发许可。 */
    Binding resolve(long tenant,long attemptId);

    /** 托管引用只供可信后台适配器使用，配置版本不等同于密钥服务版本。 */
    record Binding(long tenantId,long attemptId,String channelId,String channelCode,
        String channelMerchantNo,String currency,String credentialRef,long configurationVersion) {
        /** 防日志意外泄漏托管引用与收款主体。 */
        @Override public String toString() { return "PaymentChannelBinding[REDACTED]"; }
    }
}
