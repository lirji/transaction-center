package com.lrj.trade.order.model;

/** 地址密文及密钥版本，密钥本体不进入数据库或接口响应。 */
public record EncryptedAddress(byte[] recipient,byte[] phone,byte[] address,String keyVersion) {}
