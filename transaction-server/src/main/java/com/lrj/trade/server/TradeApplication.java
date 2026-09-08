package com.lrj.trade.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;

/** 交易中心组合入口，领域模块独立编译且使用同一数据源事务。 */
@SpringBootApplication(scanBasePackages="com.lrj.trade")
@MapperScan(basePackages="com.lrj.trade", annotationClass=Mapper.class)
public class TradeApplication {
    /** 启动只加载交易模块，不隐式修改现有认证或营销项目。 */
    public static void main(String[] args) { SpringApplication.run(TradeApplication.class,args); }
}
