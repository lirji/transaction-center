package com.lrj.trade.jobs;
import com.lrj.trade.core.*;
import com.lrj.trade.jobs.mapper.AdminJobMapper;
import org.springframework.stereotype.Service;
/** 复用调用方只读快照，不启动恢复任务，也不泄漏任意历史报文。 */
@Service
public class AdminProcessingReasonService implements AdminProcessingReasonPort {
    private final AdminJobMapper mapper;
    /** 领域通过Port读取任务分类，不跨模块调用Mapper。 */
    public AdminProcessingReasonService(AdminJobMapper mapper) { this.mapper=mapper; }
    /** 仅返回已经持久化的固定格式分类，缺少或不合法时保持未知。 */
    public String refundReason(long tenant,String refundNo) {
        Checks.positive(tenant);String code=mapper.refundReason(tenant,refundNo);
        return code!=null && code.matches("[A-Z][A-Z0-9_]{0,63}")?code:null;
    }
}
