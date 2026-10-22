package com.dkd.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.dkd.ai.common.ToolCallContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
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
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(userInput));
        String answer = callWithToolCheck(messages, userInput);
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

        // 2. 调用 LLM（带 Function Calling + 后处理校验）
        String answer = callWithToolCheck(messages, userInput);
        log.debug("[会话{}] AI 回答: {}", sessionId, answer);

        // 3. 存历史
        saveHistory(sessionId, userInput, answer);

        return answer;
    }

    /**
     * 带工具调用校验的 LLM 调用。
     * 如果 AI 声称"已创建工单"但本次没有任何工具被调用，说明在编造，强制重答（最多重试 2 次）。
     */
    private String callWithToolCheck(List<Message> messages, String userInput) {
        final int maxRetry = 3;
        for (int i = 0; i <= maxRetry; i++) {
            ToolCallContext.reset();
            ChatResponse response;
            try {
                response = chatClient.prompt()
                        .messages(messages)
                        .functions(functionCallbacks)
                        .call()
                        .chatResponse();
            } catch (IllegalStateException e) {
                // 工具层抛出的系统错误（如帝可得 token 过期）：直接如实告知，不交给大模型润色
                log.error("工具调用失败: {}", e.getMessage());
                return "系统提示：" + e.getMessage();
            }
            String answer = response.getResult().getOutput().getContent();

            // 校验1：用户明确要求创建工单但 AI 没调任何工具 → 强制重答
            if (userRequestsTaskCreation(userInput) && !ToolCallContext.hasToolCall()) {
                log.warn("用户要求创建工单但 AI 未调用任何工具，第{}次重答", i + 1);
                if (i < maxRetry) {
                    messages.add(new AssistantMessage(answer));
                    messages.add(new UserMessage(
                            "【强制规则】用户明确要求创建工单，但你没有调用任何工具就直接回复了，这是严格禁止的！" +
                            "你必须先调用查询工具（getVendingMachineById/getVendingMachineByInnerCode）获取设备当前状态，" +
                            "再根据工单类型调用对应工具（维修→createRepairTask，补货→先调 listChannelDetails 再调 createSupplyTask，投放/撤机→createOperateTask）创建工单。" +
                            "绝对禁止不调用工具就直接判断'无需维修'或'无需补货'。"));
                    continue;
                }
            }

            // 校验1.5：补货场景必须调 listChannelDetails 或 createSupplyTask（补货与 runningStatus 无关）
            if (userInput != null && userInput.contains("补货")
                    && !ToolCallContext.hasCalled("listChannelDetails")
                    && !ToolCallContext.hasCalled("createSupplyTask")) {
                log.warn("补货场景未调用 listChannelDetails/createSupplyTask，第{}次重答", i + 1);
                if (i < maxRetry) {
                    messages.add(new AssistantMessage(answer));
                    messages.add(new UserMessage(
                            "【强制规则】用户要求补货，但你没有调用 listChannelDetails 查看货道缺货明细，也没有调用 createSupplyTask 创建补货工单。" +
                            "补货与设备运行状态（runningStatus）完全无关！即使 runningStatus=正常，也必须查货道。" +
                            "【你现在必须立即调用 listChannelDetails 工具】，innerCode 参数使用你刚才从 getVendingMachineById 返回结果中看到的设备编号。" +
                            "调用 listChannelDetails 后，如果有缺货货道则展示给用户确认，确认后调用 createSupplyTask 创建补货工单。" +
                            "只有 listChannelDetails 返回所有货道可补数量均为0时，才可以说'设备货道已满，无需补货'。" +
                            "禁止仅因 runningStatus=正常就说'无需补货'，禁止跳过 listChannelDetails 直接回答！"));
                    continue;
                }
            }

            // 校验2：声称创建了工单但没调创建工单工具 → 强制重答
            if (claimsTaskCreated(answer)
                    && !ToolCallContext.hasCalled("createRepairTask")
                    && !ToolCallContext.hasCalled("createSupplyTask")
                    && !ToolCallContext.hasCalled("createOperateTask")) {
                log.warn("AI 未调用创建工单工具就声称创建成功，第{}次重答", i + 1);
                if (i < maxRetry) {
                    messages.add(new AssistantMessage(answer));
                    messages.add(new UserMessage(
                            "【强制规则】你声称创建了工单，但没有调用 createSupplyTask（补货）/createRepairTask（维修）/createOperateTask（投放撤机）中的任何一个创建工单工具！" +
                            "查询设备、查询员工只是准备步骤，**必须调用对应的 createXxxTask 工具才能真正创建工单**。" +
                            "请现在调用创建工单工具，然后根据工具返回的真实工单编号回复用户。" +
                            "绝对禁止在未调用 createXxxTask 工具的情况下编造工单编号或创建结果。"));
                    continue;
                }
            }
            return answer;
        }
        return "抱歉，创建工单操作失败，请稍后重试。";
    }

    /**
     * 判断用户是否明确要求创建工单（需要工具调用但可能没调）
     */
    private boolean userRequestsTaskCreation(String userInput) {
        if (userInput == null) return false;
        return userInput.contains("该维修") || userInput.contains("该修了")
                || userInput.contains("需要维修") || userInput.contains("修一下")
                || userInput.contains("该补货") || userInput.contains("需要补货")
                || userInput.contains("补一下") || userInput.contains("补点货")
                || userInput.contains("该投放") || userInput.contains("该撤机")
                || userInput.contains("创建工单") || userInput.contains("建个工单")
                || userInput.contains("开个工单");
    }

    /**
     * 判断 AI 回复是否声称"已创建工单"（即需要工具调用但可能没调）
     */
    private boolean claimsTaskCreated(String answer) {
        if (answer == null) return false;
        return answer.contains("工单编号")
                || answer.contains("已创建")
                || answer.contains("创建成功")
                || answer.contains("创建了")
                || answer.contains("已为");
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
