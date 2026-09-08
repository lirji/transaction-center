package com.lrj.trade.refund.mapper;

import com.lrj.trade.refund.model.ApprovalReceiptRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 审批密文证据独立持久化边界，不直接操作资金或申请状态。 */
@Mapper
public interface ApprovalReceiptMapper {
    /** 首次插入永久事件，重复不覆盖任何原始证据。 */
    int insert(@Param("tenant") long tenant,@Param("number") String number,@Param("issuer") String issuer,
        @Param("subject") String subject,@Param("event") String event,@Param("refund") String refund,
        @Param("facts") String facts,@Param("hash") String hash,@Param("cipher") byte[] cipher,@Param("key") String key);
    /** 插入后当前读，跨租户复用来源事件时查询为空并拒绝受理。 */
    List<ApprovalReceiptRecord> lockEvent(@Param("tenant") long tenant,@Param("issuer") String issuer,
        @Param("subject") String subject,@Param("event") String event);
}
