package com.lrj.trade.payment.model;

import java.time.Instant;

/** 不可变渠道配置内部视图，托管引用只供后台适配器使用。 */
public record ChannelConfiguration(String channelId,String merchantNo,String channelCode,String channelMerchantNo,String currency,String credentialRef,String returnUrlId,String returnUrl,String status,long version) {
    /** 托管引用和回跳配置不进入日志。 */
    @Override public String toString() { return "ChannelConfiguration[REDACTED]"; }
}
