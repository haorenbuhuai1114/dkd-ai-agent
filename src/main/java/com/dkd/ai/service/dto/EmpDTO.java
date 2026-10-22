package com.dkd.ai.service.dto;

import lombok.Data;

/**
 * 员工 DTO（从帝可得接口查询到的员工信息）
 *
 * 字段对应帝可得 com.dkd.manage.domain.Emp
 * 只取 AI 需要用到的关键字段
 */
@Data
public class EmpDTO {
    /** 员工ID，AI 创建工单时填到 userId 里 */
    private Long userId;
    /** 员工姓名 */
    private String userName;
    /** 所属区域ID，用来和设备的 regionId 匹配 */
    private Long regionId;
    /** 角色：运营人员（补货）/ 维修员（投放、维修、撤机） */
    private String roleName;
    /** 角色编码：1002-运营人员 1003-维修员 */
    private String roleCode;
}
