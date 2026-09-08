package com.lrj.trade.member;
import java.util.List;
import com.lrj.trade.core.*;
import com.lrj.trade.member.model.MemberPaidEvidenceRow;
import com.lrj.trade.member.mapper.MemberPaidEvidenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 领域内部批量读取，普通一致读复用编排层主库快照。 */
@Service
public class MemberPaidEvidenceService implements MemberPaidEvidencePort {
    private final MemberPaidEvidenceMapper mapper;
    /** 限定持久化边界，不跨域读取Mapper。 */
    public MemberPaidEvidenceService(MemberPaidEvidenceMapper mapper) { this.mapper=mapper; }
    /** 不自行开启新事务，保证金额、首单和订单版本来自同一个快照。 */
    @Override @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public List<MemberPaidEvidenceRow> read(long tenant,List<Long> orders) {
        Checks.positive(tenant);
        if(orders==null || orders.isEmpty() || orders.size()>100 || orders.stream().anyMatch(id->id==null || id<=0))
            throw new TradeException("INVALID_ARGUMENT","证据订单列表必须包含1到100个有效编号");
        return mapper.read(tenant,List.copyOf(orders));
    }
}
