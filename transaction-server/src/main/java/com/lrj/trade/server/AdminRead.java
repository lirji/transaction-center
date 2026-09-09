package com.lrj.trade.server;
import java.lang.annotation.*;
/** 标记受独立配额和禁止缓存约束的管理读取入口，包括只读POST。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE,ElementType.METHOD})
public @interface AdminRead {}
