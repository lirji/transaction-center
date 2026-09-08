package com.lrj.trade.order.model;
/** 申请查询与当前锁定视图，业务ID在JSON中始终为字符串。 */
public record ReturnRequest(String returnNo,String orderNo,String orderItemId,String warehouseId,long quantity,String status,String receiptNo,long version) {}
