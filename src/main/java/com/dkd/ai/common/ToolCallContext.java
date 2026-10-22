package com.dkd.ai.common;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 工具调用标记（ThreadLocal）
 *
 * 用于方案 B 后处理校验：记录本次请求有哪些 Function Calling 工具被实际调用。
 * AgentService 拿到 AI 回复后，根据调用的工具名判断是否满足业务流程要求。
 */
public class ToolCallContext {

    private static final ThreadLocal<Set<String>> CALLED_TOOLS = ThreadLocal.withInitial(HashSet::new);

    /** 工具被调用时标记，传入工具名（如 getVendingMachineById、listChannelDetails） */
    public static void markCalled(String toolName) {
        CALLED_TOOLS.get().add(toolName);
    }

    /** 本次请求是否有工具被调用 */
    public static boolean hasToolCall() {
        return !CALLED_TOOLS.get().isEmpty();
    }

    /** 本次请求是否调用过指定工具 */
    public static boolean hasCalled(String toolName) {
        return CALLED_TOOLS.get().contains(toolName);
    }

    /** 本次请求调用过的所有工具名（只读） */
    public static Set<String> getCalledTools() {
        return Collections.unmodifiableSet(CALLED_TOOLS.get());
    }

    /** 每次 LLM 调用前重置（重试时用） */
    public static void reset() {
        CALLED_TOOLS.get().clear();
    }
}
