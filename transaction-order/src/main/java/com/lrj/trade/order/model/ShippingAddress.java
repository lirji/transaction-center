package com.lrj.trade.order.model;

/** 仅作为受保护写入请求使用，不作为默认订单查询的返回字段。 */
public record ShippingAddress(String recipient,String phone,String address) {
    /** 防止后续误记请求对象时泄露收件人和联系方式。 */
    @Override public String toString() { return "ShippingAddress[REDACTED]"; }
}
