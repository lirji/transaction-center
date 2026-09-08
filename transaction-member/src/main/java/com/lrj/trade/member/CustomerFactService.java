package com.lrj.trade.member;
import java.util.List;
import com.lrj.trade.core.Checks;
import com.lrj.trade.member.mapper.CustomerFactMapper;
import com.lrj.trade.member.model.CustomerFact;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 会员域自身表普通连接读取，避免注册与消费事实分属不同快照。 */
@Service
public class CustomerFactService implements CustomerFactPort {
    private final CustomerFactMapper mapper;
    /** 只访问会员域自己的Mapper。 */
    public CustomerFactService(CustomerFactMapper mapper) { this.mapper=mapper; }
    /** 冻结会员仍保留消费历史；策略与交易权限分别判断。 */
    @Override @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public List<CustomerFact> read(long tenant,long member) {
        Checks.positive(tenant); Checks.positive(member); return mapper.read(tenant,member);
    }
}
