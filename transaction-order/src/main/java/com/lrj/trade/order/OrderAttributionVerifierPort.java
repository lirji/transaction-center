package com.lrj.trade.order;

import com.lrj.trade.core.Actor;
import com.lrj.trade.order.model.VerifiedOrderAttribution;

/** 营销令牌验证边界；实现必须在事务外验签，不能查询或改变奖励资格。 */
public interface OrderAttributionVerifierPort {
    /** 返回已认证且绑定实际主体、会员和报价的不可变声明，不保留原令牌。 */
    VerifiedOrderAttribution verify(Actor actor,long member,long quote,String token);
}
