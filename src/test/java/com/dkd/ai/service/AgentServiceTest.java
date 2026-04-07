package com.dkd.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AgentService 单元测试
 *
 * 重点验证：
 * 1. Redis 读写逻辑正确（存入历史、读取历史）
 * 2. TTL 设置为 7 天（TimeUnit.DAYS）
 * 3. 历史消息超过 MAX_HISTORY_SIZE(20) 时自动截断（滑动窗口）
 * 4. 清除会话历史时正确调用 delete
 *
 * 说明：ChatClient 和 FunctionCallback 用 Mock 占位，不实际调用 LLM
 *      saveHistory 是 private 方法，通过反射调用测试
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentServiceTest {

    @Mock
    private ChatClient chatClient;

    /** FunctionCallback 是数组类型，Mockito 不支持 mock 数组，手动 new 一个空数组 */
    private FunctionCallback[] functionCallbacks = new FunctionCallback[0];

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private AgentService agentService;

    /** 通过反射调用的 saveHistory 方法 */
    private Method saveHistoryMethod;

    @BeforeEach
    void setUp() throws Exception {
        // Mock redisTemplate.opsForValue() 返回 valueOperations
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // 通过反射获取 private saveHistory 方法
        saveHistoryMethod = AgentService.class.getDeclaredMethod(
                "saveHistory", String.class, String.class, String.class);
        saveHistoryMethod.setAccessible(true);
    }

    @Test
    @DisplayName("首次保存历史：Redis 为空时，创建新数组并存入 2 条消息，TTL=7天")
    void testSaveHistory_firstTime() throws Exception {
        // ===== 准备 =====
        String sessionId = "test-session-001";
        String userInput = "86号机该维修了";
        String aiReply = "已查询到异常设备，是否创建工单？";

        // Mock: Redis 里没有历史
        when(valueOperations.get("ai:chat:history:" + sessionId)).thenReturn(null);

        System.out.println("========== 测试1：首次保存历史 ==========");
        System.out.println("[输入] sessionId=" + sessionId);
        System.out.println("[输入] userInput=" + userInput);
        System.out.println("[输入] aiReply=" + aiReply);
        System.out.println("[Mock] Redis 返回 null（无历史）");

        // ===== 执行 =====
        saveHistoryMethod.invoke(agentService, sessionId, userInput, aiReply);

        // ===== 验证 =====
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("ai:chat:history:" + sessionId),
                jsonCaptor.capture(),
                eq(7L),
                eq(TimeUnit.DAYS)  // 关键：验证 TTL 单位是天
        );

        JSONArray history = JSON.parseArray(jsonCaptor.getValue());
        assertThat(history).hasSize(2);  // 1 条 user + 1 条 assistant

        JSONObject userMsg = history.getJSONObject(0);
        assertThat(userMsg.getString("role")).isEqualTo("user");
        assertThat(userMsg.getString("content")).isEqualTo(userInput);

        JSONObject aiMsg = history.getJSONObject(1);
        assertThat(aiMsg.getString("role")).isEqualTo("assistant");
        assertThat(aiMsg.getString("content")).isEqualTo(aiReply);

        System.out.println("[验证] Redis Key = ai:chat:history:" + sessionId);
        System.out.println("[验证] TTL = 7 天 (TimeUnit.DAYS)");
        System.out.println("[验证] 存入消息数 = " + history.size() + " (期望 2)");
        System.out.println("[验证] 存入内容 = " + jsonCaptor.getValue());
        System.out.println("[✓ 通过] 首次保存历史测试通过");
        System.out.println("");
    }

    @Test
    @DisplayName("追加历史：Redis 已有历史时，追加新消息到末尾")
    void testSaveHistory_append() throws Exception {
        // ===== 准备 =====
        String sessionId = "test-session-002";
        String existingHistory = "[{\"role\":\"user\",\"content\":\"查异常设备\"}," +
                "{\"role\":\"assistant\",\"content\":\"找到1台异常设备\"}]";

        when(valueOperations.get("ai:chat:history:" + sessionId))
                .thenReturn(existingHistory);

        System.out.println("========== 测试2：追加历史 ==========");
        System.out.println("[输入] sessionId=" + sessionId);
        System.out.println("[Mock] Redis 已有历史 = " + existingHistory);
        System.out.println("[输入] 新增 userInput=需要创建工单");
        System.out.println("[输入] 新增 aiReply=工单已创建");

        // ===== 执行 =====
        saveHistoryMethod.invoke(agentService, sessionId, "需要创建工单", "工单已创建");

        // ===== 验证 =====
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("ai:chat:history:" + sessionId),
                jsonCaptor.capture(),
                eq(7L),
                eq(TimeUnit.DAYS)
        );

        JSONArray history = JSON.parseArray(jsonCaptor.getValue());
        assertThat(history).hasSize(4);  // 原有 2 条 + 新增 2 条

        // 验证最后两条是新增的
        assertThat(history.getJSONObject(2).getString("content")).isEqualTo("需要创建工单");
        assertThat(history.getJSONObject(3).getString("content")).isEqualTo("工单已创建");

        System.out.println("[验证] 追加后消息数 = " + history.size() + " (期望 4)");
        System.out.println("[验证] 第3条 = " + history.getJSONObject(2).getString("content") + " (期望：需要创建工单)");
        System.out.println("[验证] 第4条 = " + history.getJSONObject(3).getString("content") + " (期望：工单已创建)");
        System.out.println("[验证] 完整内容 = " + jsonCaptor.getValue());
        System.out.println("[✓ 通过] 追加历史测试通过");
        System.out.println("");
    }

    @Test
    @DisplayName("滑动窗口：历史超过 20 条时，自动移除最早的消息")
    void testSaveHistory_slidingWindow() throws Exception {
        // ===== 准备：构造 20 条历史（10 轮对话）=====
        String sessionId = "test-session-003";
        StringBuilder historyBuilder = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) historyBuilder.append(",");
            historyBuilder.append(String.format(
                    "{\"role\":\"%s\",\"content\":\"消息%d\"}",
                    i % 2 == 0 ? "user" : "assistant", i));
        }
        historyBuilder.append("]");

        when(valueOperations.get("ai:chat:history:" + sessionId))
                .thenReturn(historyBuilder.toString());

        System.out.println("========== 测试3：滑动窗口截断 ==========");
        System.out.println("[输入] sessionId=" + sessionId);
        System.out.println("[Mock] Redis 已有 20 条历史");
        System.out.println("[输入] 新增 userInput=新消息, aiReply=新回复");
        System.out.println("[期望] 追加后变 22 条，截断后应保留 20 条，最早的'消息0'被移除");

        // ===== 执行：再追加 2 条（变成 22 条，应截断到 20 条）=====
        saveHistoryMethod.invoke(agentService, sessionId, "新消息", "新回复");

        // ===== 验证：截断后仍是 20 条，且最早的消息被移除 =====
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("ai:chat:history:" + sessionId),
                jsonCaptor.capture(),
                eq(7L),
                eq(TimeUnit.DAYS)
        );

        JSONArray history = JSON.parseArray(jsonCaptor.getValue());
        assertThat(history).hasSize(20);  // 必须是 20，不能超

        // 最早的"消息0"应该被移除了，现在最早的是"消息2"
        assertThat(history.getJSONObject(0).getString("content")).isEqualTo("消息2");
        // 最后一条是"新回复"
        assertThat(history.getJSONObject(19).getString("content")).isEqualTo("新回复");

        System.out.println("[验证] 截断后消息数 = " + history.size() + " (期望 20)");
        System.out.println("[验证] 最早的消息 = " + history.getJSONObject(0).getString("content") + " (期望：消息2)");
        System.out.println("[验证] 最新的消息 = " + history.getJSONObject(19).getString("content") + " (期望：新回复)");
        System.out.println("[✓ 通过] 滑动窗口截断测试通过");
        System.out.println("");
    }

    @Test
    @DisplayName("TTL 验证：存入时必须设置 7 天过期时间")
    void testSaveHistory_ttlIs7Days() throws Exception {
        // ===== 准备 =====
        String sessionId = "test-session-ttl";

        when(valueOperations.get("ai:chat:history:" + sessionId)).thenReturn(null);

        System.out.println("========== 测试4：TTL 验证 ==========");
        System.out.println("[输入] sessionId=" + sessionId);
        System.out.println("[期望] set() 必须传 7L + TimeUnit.DAYS");
        System.out.println("[期望] 不能用 TimeUnit.MINUTES");

        // ===== 执行 =====
        saveHistoryMethod.invoke(agentService, sessionId, "测试", "回复");

        // ===== 验证：必须传 7L 和 TimeUnit.DAYS =====
        verify(valueOperations).set(
                anyString(),
                anyString(),
                eq(7L),           // 关键：数量是 7
                eq(TimeUnit.DAYS)  // 关键：单位是天
        );

        // 确保没有用 MINUTES 或其他单位
        verify(valueOperations, never()).set(
                anyString(), anyString(), anyLong(), eq(TimeUnit.MINUTES));

        System.out.println("[验证] set() 传了 7L + TimeUnit.DAYS ✓");
        System.out.println("[验证] 未使用 TimeUnit.MINUTES ✓");
        System.out.println("[✓ 通过] TTL=7天 验证通过");
        System.out.println("");
    }

    @Test
    @DisplayName("清除历史：调用 delete 删除指定 sessionId 的 key")
    void testClearHistory() {
        // ===== 准备 =====
        String sessionId = "test-session-clear";

        System.out.println("========== 测试5：清除历史 ==========");
        System.out.println("[输入] sessionId=" + sessionId);
        System.out.println("[期望] 调用 redisTemplate.delete(\"ai:chat:history:" + sessionId + "\")");

        // ===== 执行 =====
        agentService.clearHistory(sessionId);

        // ===== 验证 =====
        verify(redisTemplate, times(1)).delete("ai:chat:history:" + sessionId);

        System.out.println("[验证] redisTemplate.delete() 被调用 1 次 ✓");
        System.out.println("[验证] 删除的 Key = ai:chat:history:" + sessionId);
        System.out.println("[✓ 通过] 清除历史测试通过");
        System.out.println("");
    }

    @Test
    @DisplayName("Key 前缀验证：所有 Redis 操作都使用 ai:chat:history: 前缀")
    void testSaveHistory_keyPrefix() throws Exception {
        // ===== 准备 =====
        String sessionId = "prefix-test";

        when(valueOperations.get("ai:chat:history:" + sessionId)).thenReturn(null);

        System.out.println("========== 测试6：Key 前缀验证 ==========");
        System.out.println("[输入] sessionId=" + sessionId);
        System.out.println("[期望] get/set 使用的 Key = ai:chat:history:" + sessionId);

        // ===== 执行 =====
        saveHistoryMethod.invoke(agentService, sessionId, "msg", "reply");

        // ===== 验证：get 和 set 都用了正确的 key =====
        verify(valueOperations).get("ai:chat:history:" + sessionId);
        verify(valueOperations).set(
                eq("ai:chat:history:" + sessionId),
                anyString(),
                eq(7L),
                eq(TimeUnit.DAYS)
        );

        System.out.println("[验证] get() 用的 Key = ai:chat:history:" + sessionId + " ✓");
        System.out.println("[验证] set() 用的 Key = ai:chat:history:" + sessionId + " ✓");
        System.out.println("[✓ 通过] Key 前缀验证通过");
        System.out.println("");
    }
}
