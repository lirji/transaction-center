package com.lrj.trade.server;

import com.lrj.trade.core.AuthoritativeSubjectMappingPort;
import org.springframework.stereotype.Component;

/** 权威来源尚未签收时的正式默认实现；没有任何READY写入入口或本地身份猜测。 */
@Component
public final class UnavailableSubjectMapping implements AuthoritativeSubjectMappingPort {
    /** 本地不存在与权威NOT_FOUND不同，默认始终保持PENDING。 */
    @Override public Resolution resolve(Query query) { return new Pending(query); }
}
