package com.lrj.trade.server;

import com.lrj.trade.core.AuthoritativeSubjectMappingPort;
import com.lrj.trade.core.AuthoritativeSubjectMappingPort.*;
import com.lrj.trade.core.TradeException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** C02/C03内部就绪检查；独立于原生memberId接口，未签收策略与范围前不产生奖励证据。 */
@Service
public final class AuthoritativeReferralMappingAdapter {
    private final AuthoritativeSubjectMappingPort mappings;
    private final Clock clock;

    /** 仅注入服务端受控Port，不接受调用方提供来源、映射结果或资格布尔值。 */
    public AuthoritativeReferralMappingAdapter(AuthoritativeSubjectMappingPort mappings,Clock clock) {
        this.mappings=mappings; this.clock=clock;
    }

    /** C02保留MERGED/权威NOT_FOUND区别；映射成功仍须等待策略适配与一致性边界。 */
    public Readiness customer(long tenant,String canonicalSubject,Instant asOf,String policyCode,String policyVersion) {
        requirePolicy(policyCode); requirePolicy(policyVersion);
        if(asOf==null || asOf.isAfter(clock.instant())) throw invalid();
        return check(new Query(tenant,canonicalSubject,asOf),false);
    }

    /** C03期间形状单独建模；合并不能变成NOT_FOUND，更不能选第二单或自动改查目标主体。 */
    public Readiness firstOrder(long tenant,String canonicalSubject,Instant from,Instant to,String firstOrderPolicyVersion) {
        requirePolicy(firstOrderPolicyVersion);
        if(from==null || to==null || !from.isBefore(to)) throw invalid();
        return check(new Query(tenant,canonicalSubject,clock.instant()),true);
    }

    private Readiness check(Query query,boolean order) {
        Resolution result;
        // 来源故障只表示不可用；异常可能包含主体或内部地址，不向调用方传播或记录原文。
        try { result=mappings.resolve(query); }
        catch(RuntimeException unavailable) { return pending(Reason.AUTHORITY_UNAVAILABLE); }
        // 除主体精确字节/租户外也校验观察水位；未来生效的事实不能提前授予身份。
        if(result==null || !query.equals(result.query())) return pending(Reason.MAPPING_INCONSISTENT);
        if(result instanceof Pending) return pending(Reason.AUTHORITY_UNAVAILABLE);
        Authority authority=result instanceof Mapped m?m.authority():result instanceof Merged m?m.authority():((NotFound)result).authority();
        if(authority.effectiveAt().isAfter(query.observedAt())) return pending(Reason.MAPPING_NOT_EFFECTIVE);
        if(result instanceof Merged) return order?pending(Reason.SUBJECT_MERGED):new Readiness(State.MERGED,Reason.SUBJECT_MERGED);
        // 主体不存在不能替代期间订单不存在证明；C03还没有接同快照订单事实。
        if(result instanceof NotFound) return order?pending(Reason.AUTHORITATIVE_NOT_FOUND):new Readiness(State.NOT_FOUND,Reason.AUTHORITATIVE_NOT_FOUND);
        // 原生policy名/version=0/factVersion/paidAt不能隐式升级为外部合同。
        return pending(order?Reason.ORDER_POLICY_SCOPE_UNCONFIRMED:Reason.CUSTOMER_POLICY_UNCONFIRMED);
    }
    private static Readiness pending(Reason reason) { return new Readiness(State.PENDING,reason); }
    private static void requirePolicy(String value) { if(value==null || value.isBlank()) throw invalid(); }
    private static TradeException invalid() { return new TradeException("INVALID_ARGUMENT","权威证据查询的策略或时间无效"); }

    /** 内部就绪状态不含READY，不能序列化成已经签收的外部C02/C03证据。 */
    public enum State { PENDING,MERGED,NOT_FOUND }
    /** 明确缺失门禁，使调用方无需把PENDING解释成false新客。 */
    public enum Reason { AUTHORITY_UNAVAILABLE,MAPPING_INCONSISTENT,MAPPING_NOT_EFFECTIVE,SUBJECT_MERGED,AUTHORITATIVE_NOT_FOUND,CUSTOMER_POLICY_UNCONFIRMED,ORDER_POLICY_SCOPE_UNCONFIRMED }
    /** 不返回canonicalSubject、会员编号或虚假的evidenceId，避免内部诊断泄露身份。 */
    public record Readiness(State state,Reason reason) {}
}
