package com.dkd.ai.service.dto;

import lombok.Data;

/**
 * 工单状态查询返回 DTO
 *
 * 用于 AI 查询工单实时状态，避免依赖历史记录中过期的"已创建"信息
 */
@Data
public class TaskStatusDTO {
    /** 工单ID */
    private Long taskId;
    /** 工单编号（如 202608030001） */
    private String taskCode;
    /** 工单状态：1-待处理 2-进行中 3-已取消 4-已完成 */
    private Long taskStatus;
    /** 工单状态中文名（待处理/进行中/已取消/已完成） */
    private String taskStatusName;
    /** 工单类型：1-投放 2-补货 3-维修 4-撤机 */
    private Long productTypeId;
    /** 工单类型中文名（投放/补货/维修/撤机） */
    private String productTypeName;
    /** 设备编号 */
    private String innerCode;
    /** 执行人姓名 */
    private String userName;
    /** 设备地址 */
    private String addr;
    /** 故障/工单描述 */
    private String desc;
    /** 创建时间 */
    private String createTime;
    /** 更新时间 */
    private String updateTime;
}
