package com.lrj.trade.server;

/** 已持久审计的拒绝响应，审计编号可关联排查，不携带JWT或原异常堆栈。 */
public final class ApprovalRejection extends RuntimeException {
    private final String code;
    private final String auditId;
    /** 只从受控业务异常创建，不接受客户端提供审计编号。 */
    public ApprovalRejection(String code,String message,String auditId) {
        super(message); this.code=code; this.auditId=auditId;
    }
    /** 保持原业务拒绝分类。 */
    public String code() { return code; }
    /** 使用永久审计资源编号作为响应关联号。 */
    public String auditId() { return auditId; }
}
