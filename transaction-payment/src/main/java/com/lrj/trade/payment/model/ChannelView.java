package com.lrj.trade.payment.model;

/** 后台配置摘要，不返回密钥托管引用。 */
public record ChannelView(String channelId,String merchantNo,String channelCode,String channelMerchantNo,
    String currency,String returnUrlId,String status,long version) {}
