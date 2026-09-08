package com.lrj.trade.member;
import java.util.List;
import com.lrj.trade.member.model.CustomerFact;
/** 资格策略所需的权威交易原生事实，不负责canonicalSubject映射或营销归因。 */
public interface CustomerFactPort {
    /** 加入查询编排快照；其他租户与不存在均返回空列表。 */
    List<CustomerFact> read(long tenant,long member);
}
