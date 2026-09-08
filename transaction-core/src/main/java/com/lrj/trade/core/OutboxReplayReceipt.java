package com.lrj.trade.core;
/** 永久幂等凭证与首次结果；命令缓存淘汰后仍禁止同键更换事件或原因。 */
public record OutboxReplayReceipt(long id,String eventId,String requestHash,Long resultVersion,Long resultReplayCount) {}
