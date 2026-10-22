package com.dkd.ai.controller;

import com.dkd.ai.common.Result;
import com.dkd.ai.common.TokenContext;
import com.dkd.ai.service.AgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * AI Agent 控制器
 *
 * 前端 / 运维 App / 运维小程序 都通过这个接口和 AI 对话
 *
 * 接口列表：
 * 1. POST /ai/chat          ：用户和 AI 对话（核心接口，带 sessionId 多轮记忆）
 * 2. POST /ai/clearHistory  ：清除会话历史
 * 3. GET  /ai/health        ：健康检查（服务是否可用）
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AgentController {

    @Autowired
    private AgentService agentService;

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Result<String> health() {
        return Result.success("dkd-ai-agent is running");
    }

    /**
     * AI 对话接口（多轮记忆）
     *
     * @param req.sessionId 会话ID（前端生成 UUID，同一会话复用），不传则自动生成
     * @param req.message   用户问题，如 "3号机该维修了"、"查一下异常设备"、"需要"、"许褚"
     * @return AI 的最终回答（自然语言）
     */
    @PostMapping("/chat")
    public Result<String> chat(@RequestBody UserChatRequest req,
                               @RequestHeader(value = "Authorization", required = false) String authToken) {
        try {
            // token 透传：前端带的帝可得登录 token 存 ThreadLocal，DkdClient 调帝可得接口时取出
            if (authToken != null && !authToken.isEmpty()) {
                TokenContext.setToken(authToken);
            }
            // sessionId 不传时，自动生成一个（兼容老接口调用）
            String sessionId = req.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = "s_" + System.currentTimeMillis();
            }
            String answer = agentService.chatWithHistory(sessionId, req.getMessage());
            return Result.success(answer);
        } catch (Exception e) {
            log.error("AI 对话失败", e);
            return Result.error("AI 对话失败: " + e.getMessage());
        } finally {
            // 必须清理：Tomcat 线程池复用线程，不清会串到下一个请求的用户
            TokenContext.clear();
        }
    }

    /**
     * 清除会话历史
     *
     * @param req.sessionId 要清除的会话ID
     */
    @PostMapping("/clearHistory")
    public Result<String> clearHistory(@RequestBody UserChatRequest req) {
        if (req.getSessionId() == null || req.getSessionId().isEmpty()) {
            return Result.error("sessionId 不能为空");
        }
        agentService.clearHistory(req.getSessionId());
        return Result.success("会话历史已清除");
    }

    /**
     * 内部 DTO：接收前端的对话请求
     */
    @lombok.Data
    public static class UserChatRequest {
        /** 会话ID（前端生成 UUID，同一会话复用） */
        private String sessionId;
        /** 用户消息内容 */
        private String message;
    }
}
