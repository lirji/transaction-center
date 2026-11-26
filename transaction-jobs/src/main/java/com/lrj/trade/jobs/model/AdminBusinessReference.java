package com.lrj.trade.jobs.model;
/** 已核对本租户归属的安全业务编号，不返回原任务键或执行凭据。 */
/** 任务引用保留原业务关联，并补充运营查单编号。 */
public record AdminBusinessReference(String resourceType,String resourceNo,String businessNo) {}
