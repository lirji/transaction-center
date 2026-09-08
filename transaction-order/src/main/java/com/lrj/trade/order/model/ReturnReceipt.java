package com.lrj.trade.order.model;
/** 独立验收永久事实，验收失败也占用实际收货数量预算。 */
public record ReturnReceipt(String returnNo,String requestHash) {}
