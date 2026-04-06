package com.dkd.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * dkd-ai-agent 启动类
 *
 * 架构说明：
 * 1. 本服务独立部署，端口 8081
 * 2. 不连接数据库，所有数据通过 HTTP 调帝可得后端接口获取
 * 3. 使用 Spring AI 调用智谱 GLM-4-Flash 大模型
 * 4. 通过 @Tool 注解 + Function Calling 实现工单自动创建
 */
@SpringBootApplication
public class AgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
        System.out.println("dkd-ai-agent 启动成功");
    }
}
