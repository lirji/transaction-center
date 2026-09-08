package com.lrj.trade.order.model;
import java.time.Instant;
/** 永久来源回执，保存首次服务器时间与首次返回结果。 */
public record FulfillmentIngress(long id,String requestHash,Instant observedAt,String resultNo,String resultStatus,Long resultVersion) {}
