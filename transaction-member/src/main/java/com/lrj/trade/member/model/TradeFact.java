package com.lrj.trade.member.model;

import java.time.Instant;

/** 不可逆消费历史快照；事实版本独立于会员资料版本，不能作为未来新客资格预占。 */
public record TradeFact(String memberId,boolean everPaid,String firstPaidOrderId,Instant firstConfirmedAt,
    long paidOrderCount,long factVersion) {}
