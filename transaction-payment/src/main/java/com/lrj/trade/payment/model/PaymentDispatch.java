package com.lrj.trade.payment.model;

/** 内部调用计划；CREATE仅首次许可，后续QUERY或CLOSE都使用原请求号。不是任务租约。 */
public record PaymentDispatch(String action,String requestNo,String channelCode,String channelMerchantNo,
    String credentialRef,long amount,String currency,String returnUrl,long attemptVersion) {
    /** 凭据托管引用和渠道参数不能被默认日志输出。 */
    @Override public String toString() { return "PaymentDispatch[REDACTED]"; }
}
