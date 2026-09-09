package com.lrj.trade.jobs.model;
/** 已核对本租户归属的安全业务编号，不返回原任务键或执行凭据。 */
public record AdminBusinessReference(String resourceType,String resourceNo) {}
