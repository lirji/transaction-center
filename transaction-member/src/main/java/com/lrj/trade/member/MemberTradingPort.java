package com.lrj.trade.member;

import com.lrj.trade.core.Actor;

/** 报价和订单通过此端口校验交易身份，不跨模块读取会员表。 */
public interface MemberTradingPort {
    /** 在调用方事务中锁定并验证会员有效，返回可信会员主键。 */
    long requireActive(Actor actor);
    /** 只解析会员归属，不因冻结阻止本人查询已有交易证据。 */
    long memberId(Actor actor);
}
