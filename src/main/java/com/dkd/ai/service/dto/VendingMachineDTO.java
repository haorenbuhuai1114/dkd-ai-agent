package com.dkd.ai.service.dto;

import lombok.Data;

/**
 * 售货机 DTO（从帝可得接口查询到的设备信息）
 *
 * 字段对应帝可得 com.dkd.manage.domain.VendingMachine
 * 只取 AI 需要用到的关键字段
 */
@Data
public class VendingMachineDTO {
    /** 设备ID */
    private Long id;
    /** 设备编号（如 VM003），AI 创建工单的核心参数 */
    private String innerCode;
    /** 设备运行状态（正常/卡货/断网/故障/离线 等） */
    private String runningStatus;
    /** 所属区域ID，用来匹配同区域的员工 */
    private Long regionId;
    /** 设备状态 0-未投放 1-运营中 2-停用 3-撤机 */
    private Integer vmStatus;
    /** 最后补货时间（格式 yyyy-MM-dd HH:mm:ss），用来判断是否该补货 */
    private String lastSupplyTime;
    /** 设备详细地址 */
    private String addr;
    /** 设备类型ID（用于匹配设备类型名称） */
    private Long vmTypeId;
    /** 设备类型名称（如：饮料机，通过 vmTypeId 匹配字典得到） */
    private String vmTypeName;
    /** 上次修改时间（格式 yyyy-MM-dd HH:mm:ss） */
    private String updateTime;
}
