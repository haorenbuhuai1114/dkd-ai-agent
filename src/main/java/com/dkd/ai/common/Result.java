package com.dkd.ai.common;

import lombok.Data;

/**
 * 统一返回结果（模仿帝可得的 AjaxResult 结构）
 *
 * 为什么不直接用帝可得的 AjaxResult？
 * - dkd-ai-agent 是独立服务，不依赖 dkd-common
 * - 自己写一个简化版，结构保持一致即可
 *
 * 结构：code(200=成功/500=失败) + msg + data
 */
@Data
public class Result<T> {
    private int code;
    private String msg;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.msg = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> error(String msg) {
        Result<T> r = new Result<>();
        r.code = 500;
        r.msg = msg;
        return r;
    }
}
