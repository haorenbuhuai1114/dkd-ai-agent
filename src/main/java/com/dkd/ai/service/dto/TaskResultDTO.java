package com.dkd.ai.service.dto;

import lombok.Data;

/**
 * 创建工单返回 DTO
 *
 * 对应帝可得创建工单接口的返回
 */
@Data
public class TaskResultDTO {
    /** 工单ID */
    private Long taskId;
    /** 工单编号（如 202608030001），展示给用户看 */
    private String taskCode;
    /** 创建结果说明 */
    private String msg;
}
