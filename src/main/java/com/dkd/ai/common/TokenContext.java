package com.dkd.ai.common;

/**
 * 当前请求的帝可得 token 上下文（ThreadLocal）
 *
 * token 透传方案：前端调 /ai/chat 时带上自己的帝可得登录 token，
 * AgentController 存入这里，DkdClient 调帝可得接口时取出透传，
 * 实现"谁聊天就是谁"的身份传递（对齐微服务间认证传递做法）。
 *
 * 注意：用完必须 clear()——Tomcat 线程池复用线程，不清会串到下一个请求的用户
 */
public class TokenContext {

    private static final ThreadLocal<String> TOKEN_HOLDER = new ThreadLocal<>();

    public static void setToken(String token) {
        TOKEN_HOLDER.set(token);
    }

    public static String getToken() {
        return TOKEN_HOLDER.get();
    }

    public static void clear() {
        TOKEN_HOLDER.remove();
    }
}
