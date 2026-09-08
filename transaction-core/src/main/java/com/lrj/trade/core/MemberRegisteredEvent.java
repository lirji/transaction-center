package com.lrj.trade.core;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 注册V1的完整不可变载荷；身份原文、展示名称和凭据不进入跨域事件。 */
public record MemberRegisteredEvent(String eventId,String eventType,int schemaVersion,String tenantId,
    String aggregateType,String aggregateId,long aggregateVersion,Instant occurredAt,String producer,String traceId,Data data) implements V1EventEnvelope {
    /** 固定类型和聚合语义，禁止索引列与载荷采用各自独立的身份参数。 */
    public MemberRegisteredEvent {
        canonicalUuid(eventId); canonicalUuid(traceId);
        if(!"trade.member-registered.v1".equals(eventType) || schemaVersion!=1 || !"MEMBER".equals(aggregateType)
            || aggregateVersion!=0 || !"transaction-center".equals(producer) || data==null
            || !aggregateId.equals(data.memberId()) || !occurredAt.equals(data.registeredAt())
            || !occurredAt.equals(occurredAt.truncatedTo(ChronoUnit.MILLIS))) throw new IllegalArgumentException("注册事件合同不一致");
        canonicalId(tenantId); canonicalId(aggregateId);
    }
    private static void canonicalId(String value) {
        long parsed=Long.parseLong(value); Checks.positive(parsed);
        if(!Long.toString(parsed).equals(value)) throw new IllegalArgumentException("事件编号必须为规范正十进制");
    }
    private static void canonicalUuid(String value) {
        if(!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("事件关联编号必须为规范UUID");
    }
    /** 新会员初始版本为零；无上游跟踪时生成本地关联，不冒充跨系统traceparent。 */
    public static MemberRegisteredEvent create(long tenant,long member,Instant registeredAt) {
        Checks.positive(tenant); Checks.positive(member);
        return new MemberRegisteredEvent(UUID.randomUUID().toString(),"trade.member-registered.v1",1,Long.toString(tenant),
            "MEMBER",Long.toString(member),0,registeredAt,"transaction-center",TraceContext.correlationUuid(),
            new Data(Long.toString(member),registeredAt,"AUTHENTICATED_IDENTITY"));
    }
    /** 来源表示本地经认证身份注册路径，不泄露issuer或subject。 */
    public record Data(String memberId,Instant registeredAt,String registrationSource) {
        /** 只接受本实现实际观察到的注册来源，不能自报其他权威平台。 */
        public Data {
            canonicalId(memberId);
            if(registeredAt==null || !"AUTHENTICATED_IDENTITY".equals(registrationSource)) throw new IllegalArgumentException("注册事实无效");
        }
    }
}
