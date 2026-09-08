package com.lrj.trade.inventory.model;
/** 永久回库操作的内容与本次插入标记，短期命令清理不影响去重。 */
public record ReturnOperation(String requestHash,String claimToken) {}
