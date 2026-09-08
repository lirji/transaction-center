package com.lrj.trade.refund;

import com.lrj.trade.refund.model.VerifiedRefundEvidence;

/** 可信证据耐久接收边界；实现必须独立事务保存必要密文及恢复任务，不依赖旧业务租约。 */
public interface RefundEvidencePort {
    /** 提交持久化才可正常返回，失败必须抛出；本方法不得直接确认退款或释放额度。 */
    void accept(long tenant,VerifiedRefundEvidence evidence);
}
