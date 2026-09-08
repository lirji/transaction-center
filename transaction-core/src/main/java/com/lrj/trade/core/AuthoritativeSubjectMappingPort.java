package com.lrj.trade.core;

import java.time.Instant;
import java.util.Objects;

/** 只读权威主体边界；实现者必须已验证来源，不能把本地缺行当作权威不存在。 */
public interface AuthoritativeSubjectMappingPort {
    /** 在指定观察时点解析；未接来源、超时或无法确认修订时必须返回Pending。 */
    Resolution resolve(Query query);

    /** canonicalSubject按原值精确匹配，不trim、不改大小写、不由本地会员编号派生。 */
    record Query(long tenantId,String canonicalSubject,Instant observedAt) {
        /** 内部调用同样必须携带有效隔离键与水位。 */
        public Query {
            Checks.positive(tenantId);
            requireSubject(canonicalSubject);
            if(observedAt==null)
                throw new TradeException("INVALID_ARGUMENT","权威主体与观察时点不能为空");
        }
        /** 避免默认record表示把个人主体写入诊断日志。 */
        @Override public String toString() { return "SubjectMappingQuery[tenantId="+tenantId+", canonicalSubject=<redacted>]"; }
    }

    /** 非PENDING结果的来源凭证；修订属于映射，不能当成customerRevision或订单版本。 */
    record Authority(String sourceId,String eventId,long revision,Instant effectiveAt) {
        /** 未知来源/零修订不能组成可用的权威凭证。 */
        public Authority {
            Checks.text(sourceId,200); Checks.text(eventId,200); Checks.positive(revision);
            Objects.requireNonNull(effectiveAt);
        }
    }

    /** 完整请求绑定防止来源适配器把其他租户或主体的结果串入当前请求。 */
    sealed interface Resolution permits Pending,Mapped,NotFound,Merged {
        /** 每个结果回显原查询，供编排层检查隔离键与水位。 */
        Query query();
    }

    /** 没有可信结果不提供布尔资格默认值。 */
    record Pending(Query query) implements Resolution {
        /** 保留失败关闭结果对应的具体查询。 */
        public Pending { Objects.requireNonNull(query); }
    }

    /** 只表示权威主体到本地会员的映射；并不证明新客、首单或店铺范围。 */
    record Mapped(Query query,Authority authority,long memberId) implements Resolution {
        /** 映射结果必须同时具有来源凭证与有效本地会员键。 */
        public Mapped { Objects.requireNonNull(query); Objects.requireNonNull(authority); Checks.positive(memberId); }
    }

    /** 仅允许权威来源明确的不存在事实；本地无映射行应返回Pending。 */
    record NotFound(Query query,Authority authority) implements Resolution {
        /** 不允许把无来源的本地缺行包装成NOT_FOUND。 */
        public NotFound { Objects.requireNonNull(query); Objects.requireNonNull(authority); }
    }

    /** 权威合并事实保留目标及合并修订，不授权重定向查询或合并消费/额度历史。 */
    record Merged(Query query,Authority authority,String newCanonicalSubject) implements Resolution {
        /** 合并必须有明确且不同的目标，不能猜测或自合并。 */
        public Merged {
            Objects.requireNonNull(query); Objects.requireNonNull(authority);
            requireSubject(newCanonicalSubject);
            if(newCanonicalSubject.equals(query.canonicalSubject()))
                throw new TradeException("INVALID_ARGUMENT","合并目标主体无效");
        }
        /** 不在日志暴露合并前后的个人主体。 */
        @Override public String toString() { return "SubjectMappingMerged[query="+query+", target=<redacted>]"; }
    }
    /** C01按Unicode字符计上限；不把补充平面字符当两个身份字符，也不允许无效代理值进入摘要。 */
    private static void requireSubject(String subject) {
        if(subject==null || subject.isBlank() || subject.codePointCount(0,subject.length())>256
            || subject.codePoints().anyMatch(code->code>=0xD800 && code<=0xDFFF))
            throw new TradeException("INVALID_ARGUMENT","权威主体无效或超过256个Unicode字符");
    }

}
