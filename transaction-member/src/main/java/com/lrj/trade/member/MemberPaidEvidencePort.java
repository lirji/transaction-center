package com.lrj.trade.member;
import java.util.List;
import com.lrj.trade.member.model.MemberPaidEvidenceRow;
/** 交易主库普通一致读，必须加入同一证据查询事务；不是资格预占。 */
public interface MemberPaidEvidencePort {
    /** 最多100个订单ID，不存在或其他租户不返回行；不启用行锁或副本查询。 */
    List<MemberPaidEvidenceRow> read(long tenant,List<Long> orders);
}
