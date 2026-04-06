package com.dkd.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AI Agent 服务
 *
 * 一行代码调用 Function Calling 循环：
 * .user(userInput)          → 设置用户问题
 * .functions(functionCallbacks) → 注入可用函数
 * .call()                   → 自动循环：调 LLM → 执行函数 → 回喂 → 再决策
 * .content()                → 获取最终回答
 *
 * 多轮对话历史：Redis 存储，按 sessionId 隔离
 * - key: ai:chat:history:{sessionId}
 * - value: JSON 数组，每个元素 {role:"user"/"assistant", content:"..."}
 * - TTL: 30 分钟
 * - 最多保留 20 条消息（防止 token 超限）
 */
@Slf4j
@Service
public class AgentService {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private FunctionCallback[] functionCallbacks;

    /** Redis 客户端，存储多轮对话历史 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** Redis key 前缀 */
    private static final String HISTORY_KEY_PREFIX = "ai:chat:history:";

    /** 历史消息 TTL（天）—— 7 天，覆盖运维场景一周内回看需求 */
    private static final int HISTORY_TTL_DAYS = 7;

    /** 最多保留的历史消息条数（滑动窗口，防止 token 超限） */
    private static final int MAX_HISTORY_SIZE = 20;

    /**
     * 单轮对话（当前 MVP 使用，无历史记忆）
     */
    public String chat(String userInput) {
        log.debug("用户问题: {}", userInput);
        String answer = chatClient.prompt()
                .user(userInput)
                .functions(functionCallbacks)
                .call()
                .content();
        log.debug("AI 回答: {}", answer);
        return answer;
    }

    /**
     * 多轮对话（Redis 历史记忆，后续对接时启用）
     *
     * 流程：
     * 1. 从 Redis 取历史消息
     * 2. 拼接历史 + 当前用户输入
     * 3. 传给 LLM（带 Function Calling）
     * 4. 将新的 user/assistant 消息存入 Redis
     *
     * @param sessionId 会话ID（前端生成，如 UUID），同一会话的多轮对话共用
     * @param userInput 用户当前输入
     * @return AI 回复
     */
    public String chatWithHistory(String sessionId, String userInput) {
        log.debug("[会话{}] 用户问题: {}", sessionId, userInput);

        // 1. 从 Redis 取历史消息
        List<Message> messages = new ArrayList<>();
        String historyJson = redisTemplate.opsForValue().get(HISTORY_KEY_PREFIX + sessionId);
        if (historyJson != null) {
            JSONArray history = JSON.parseArray(historyJson);
            for (int i = 0; i < history.size(); i++) {
                JSONObject msg = history.getJSONObject(i);
                String role = msg.getString("role");
                String content = msg.getString("content");
                if ("user".equals(role)) {
                    messages.add(new UserMessage(content));
                } else if ("assistant".equals(role)) {
                    messages.add(new AssistantMessage(content));
                }
            }
        }

        // 添加当前用户输入
        messages.add(new UserMessage(userInput));

        // 2. 调用 LLM（带 Function Calling）
        String answer = chatClient.prompt()
                .messages(messages)
                .functions(functionCallbacks)
                .call()
                .content();

        log.debug("[会话{}] AI 回答: {}", sessionId, answer);

        // 3. 存历史
        saveHistory(sessionId, userInput, answer);

        return answer;
    }

    /**
     * 保存对话历史到 Redis
     *
     * @param sessionId 会话ID
     * @param userInput 用户输入
     * @param assistantReply AI 回复
     */
    private void saveHistory(String sessionId, String userInput, String assistantReply) {
        String key = HISTORY_KEY_PREFIX + sessionId;
        String historyJson = redisTemplate.opsForValue().get(key);
        JSONArray history = historyJson != null ? JSON.parseArray(historyJson) : new JSONArray();

        // 添加新消息
        history.add(new JSONObject().fluentPut("role", "user").fluentPut("content", userInput));
        history.add(new JSONObject().fluentPut("role", "assistant").fluentPut("content", assistantReply));

        // 超过最大条数时，移除最早的消息
        while (history.size() > MAX_HISTORY_SIZE) {
            history.remove(0);
        }

        // 存入 Redis，设置 TTL（7 天）
        redisTemplate.opsForValue().set(key, history.toJSONString(), HISTORY_TTL_DAYS, TimeUnit.DAYS);
    }

    /**
     * 清除会话历史
     *
     * @param sessionId 会话ID
     */
    public void clearHistory(String sessionId) {
        redisTemplate.delete(HISTORY_KEY_PREFIX + sessionId);
        log.debug("[会话{}] 历史已清除", sessionId);
    }
}
