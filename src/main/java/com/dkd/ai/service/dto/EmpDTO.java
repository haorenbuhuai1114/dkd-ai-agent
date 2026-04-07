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
    /** 角色：运维员/运营员/补货员/维修工 */
    private String roleName;
    /** 角色编码：1001-运营 1002-运维 1003-补货员 1004-维修员 */
    private String roleCode;
}
