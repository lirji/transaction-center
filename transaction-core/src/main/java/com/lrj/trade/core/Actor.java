package com.lrj.trade.core;

/** 经签名验证的租户身份；禁止从未经验证的请求头构造生产身份。 */
public record Actor(long tenantId, String issuer, String subject) {
    /** 在所有服务边界统一约束身份长度，避免截断造成串绑。 */
    public Actor {
        Checks.positive(tenantId); Checks.text(issuer, 255); Checks.text(subject, 255);
    }
    /** 以签发方和主体共同区分身份，不把不同身份源的同名用户混合。 */
    public String key() { return Checks.hash(issuer, subject); }
}

