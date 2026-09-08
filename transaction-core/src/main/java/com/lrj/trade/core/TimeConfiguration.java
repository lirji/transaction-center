package com.lrj.trade.core;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 统一业务时钟，便于精确验证报价边界而不依赖脆弱的线程休眠。 */
@Configuration
public class TimeConfiguration {
    /** 生产使用UTC时钟，测试通过替换此依赖验证时间推进。 */
    @Bean
    public Clock tradeClock() { return Clock.systemUTC(); }
}
