package com.dkd.ai.tool;

import com.dkd.ai.service.DkdClient;
import com.dkd.ai.service.dto.EmpDTO;
import com.dkd.ai.service.dto.TaskResultDTO;
import com.dkd.ai.service.dto.TaskStatusDTO;
import com.dkd.ai.service.dto.VendingMachineDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.List;

@Slf4j
@Configuration
public class DkdTools {

    @Autowired
    private DkdClient dkdClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public FunctionCallback[] dkdFunctionCallbacks() {
        return new FunctionCallback[]{
                getVendingMachineByIdCallback(),
                getVendingMachineByInnerCodeCallback(),
                listAbnormalVendingMachinesCallback(),
                listChannelDetailsCallback(),
                listEmployeesByRegionCallback(),
                listRepairersByInnerCodeCallback(),
                listBusinessesByInnerCodeCallback(),
                createRepairTaskCallback(),
                createSupplyTaskCallback(),
                createOperateTaskCallback(),
                queryTaskStatusCallback()
        };
    }

    @Description("查询指定设备ID的设备详情：根据设备ID（如86）查询单台设备的详细信息，返回设备ID、设备编号、所属区域ID、设备地址、设备类型、设备状态、运行状态。如果该工具的返回结果可直接用于后续步骤，不需要再调用 listAbnormalVendingMachines 或 listRepairersByInnerCode 查询时优先调用此工具。")
    private FunctionCallback getVendingMachineByIdCallback() {
        return FunctionCallbackWrapper.builder((Object input) -> {
                    IdInput req = objectMapper.convertValue(input, IdInput.class);
                    log.debug("工具函数：查询设备ID={}详情", req.getId());
                    VendingMachineDTO vm = dkdClient.getVendingMachineById(req.getId());
                    if (vm == null) {
                        return "未找到设备ID=" + req.getId() + "的设备，请确认设备ID是否正确，或调用 listAbnormalVendingMachines 查询异常设备列表";
                    }
                    return String.format("设备ID:%s, 设备编号:%s, 所属区域ID:%s, 设备地址:%s, 设备类型:%s, 设备状态:%s(1-运营中,0-未投放,3-撤机), 运行状态:%s, 上次修改时间:%s",
                            vm.getId(), vm.getInnerCode(), vm.getRegionId(), vm.getAddr(),
                            vm.getVmTypeName(), vm.getVmStatus(), vm.getRunningStatus(), vm.getUpdateTime());
                })
                .withDescription("根据设备ID（数字型，如86）查询单台设备详情。参数：id（设备ID，如86）。返回：设备ID、设备编号、所属区域ID、设备地址、设备类型、设备状态、运行状态。当用户说XX号机时优先调用此工具，不要先调 listAbnormalVendingMachines。")
                .withInputType(IdInput.class)
                .withName("getVendingMachineById")
                .build();
    }

    @Description("查询指定设备编号的设备详情：根据设备编号（如aim5xu4I）查询单台设备的详细信息，返回设备ID、设备编号、所属区域ID、设备地址、设备类型、设备状态、运行状态。该工具优于调用 listAbnormalVendingMachines，当用户说设备编号时优先调用此工具，不要先查 listAbnormalVendingMachines 或 listRepairersByInnerCode 查询时优先调用此工具。")
    private FunctionCallback getVendingMachineByInnerCodeCallback() {
        return FunctionCallbackWrapper.builder((Object input) -> {
                    InnerCodeInput req = objectMapper.convertValue(input, InnerCodeInput.class);
                    log.debug("工具函数：查询设备编号={}详情", req.getInnerCode());
                    VendingMachineDTO vm = dkdClient.getVendingMachineByInnerCode(req.getInnerCode());
                    if (vm == null) {
                        return "未找到设备编号=" + req.getInnerCode() + "的设备，请确认设备编号是否正确，或调用 listAbnormalVendingMachines 查询异常设备列表";
                    }
                    return String.format("设备ID:%s, 设备编号:%s, 所属区域ID:%s, 设备地址:%s, 设备类型:%s, 设备状态:%s(1-运营中,0-未投放,3-撤机), 运行状态:%s, 上次修改时间:%s",
                            vm.getId(), vm.getInnerCode(), vm.getRegionId(), vm.getAddr(),
                            vm.getVmTypeName(), vm.getVmStatus(), vm.getRunningStatus(), vm.getUpdateTime());
                })
                .withDescription("根据设备编号（如aim5xu4I）查询单台设备详情。参数：innerCode（设备编号）。返回：设备ID、设备编号、所属区域ID、设备地址、设备类型、设备状态、运行状态。当用户给出设备编号时优先调用此工具，不要先调 listAbnormalVendingMachines。")
                .withInputType(InnerCodeInput.class)
                .withName("getVendingMachineByInnerCode")
                .build();
    }

    private FunctionCallback listAbnormalVendingMachinesCallback() {
        return FunctionCallbackWrapper.builder((Object input) -> {
                    log.debug("工具函数：查询异常设备");
                    List<VendingMachineDTO> list = dkdClient.listAbnormalVendingMachines();
                    StringBuilder sb = new StringBuilder();
                    for (VendingMachineDTO vm : list) {
                        sb.append(String.format("设备ID:%s, 设备编号:%s, 所属区域ID:%s, 设备地址:%s, 设备类型:%s, 设备状态:%s(1-运营中,0-未投放,3-撤机), 运行状态:%s, 上次修改时间:%s; ",
                                vm.getId(), vm.getInnerCode(), vm.getRegionId(), vm.getAddr(),
                                vm.getVmTypeName(), vm.getVmStatus(), vm.getRunningStatus(), vm.getUpdateTime()));
                    }
                    if (sb.length() == 0) {
                        return "没有异常设备";
                    }
                    return sb.toString();
                })
                .withName("listAbnormalVendingMachines")
                .withDescription("查询帝可得所有异常设备列表。返回字段包含：id(设备ID)、innerCode(设备编号)、addr(设备地址)、vmTypeName(设备类型名称，如饮料机)、updateTime(上次修改时间)。")
                .withInputType(EmptyInput.class)
                .build();
    }

    private FunctionCallback listChannelDetailsCallback() {
        return FunctionCallbackWrapper.builder((Object input) -> {
                    InnerCodeInput req = objectMapper.convertValue(input, InnerCodeInput.class);
                    log.debug("工具函数：查询设备 {} 货道缺货明细", req.getInnerCode());
                    String details = dkdClient.formatChannelDetails(req.getInnerCode());
                    return details;
                })
                .withName("listChannelDetails")
                .withDescription("查询指定设备的货道缺货明细。参数 innerCode 是设备编号(如aim5xu4I)。返回每个需要补货的货道信息：货道编号、商品名称、需补货数量。触发场景：用户要补货或查看缺货详情时，在查到缺货设备后调用此工具获取具体缺了什么货、要补多少。")
                .withInputType(InnerCodeInput.class)
                .build();
    }

    private FunctionCallback listEmployeesByRegionCallback() {
        return FunctionCallbackWrapper.builder((Object input) -> {
                    RegionIdInput req = objectMapper.convertValue(input, RegionIdInput.class);
                    log.debug("工具函数：查询区域 {} 员工", req.getRegionId());
                    List<EmpDTO> list = dkdClient.listEmployeesByRegion(req.getRegionId());
                    StringBuilder sb = new StringBuilder();
                    for (EmpDTO e : list) {
                        sb.append(String.format("员工ID:%s, 姓名:%s, 区域:%s, 角色编码:%s, 角色名:%s; ",
                                e.getUserId(), e.getUserName(), e.getRegionId(),
                                e.getRoleCode(), e.getRoleName()));
                    }
                    if (sb.length() == 0) {
                        return "该区域没有可用员工";
                    }
                    return sb.toString();
                })
                .withName("listEmployeesByRegion")
                .withDescription("通用查询：查询指定区域的所有员工（不限制角色）。参数 regionId 必须从设备列表中获取。返回字段包含：userId(员工ID)、userName(姓名)、regionId(所属区域)、roleCode(角色编码)、roleName(角色名)。roleCode参考：1002-运营员 1003-维修员。")
                .withInputType(RegionIdInput.class)
                .build();
    }

    private FunctionCallback listRepairersByInnerCodeCallback() {
        return FunctionCallbackWrapper.builder((Object input) -> {
                    InnerCodeInput req = objectMapper.convertValue(input, InnerCodeInput.class);
                    log.debug("工具函数：根据设备编号 {} 查询维修员", req.getInnerCode());
                    List<EmpDTO> list = dkdClient.listRepairersByInnerCode(req.getInnerCode());
                    StringBuilder sb = new StringBuilder();
                    for (EmpDTO e : list) {
                        sb.append(String.format("员工ID:%s, 姓名:%s, 区域:%s, 角色编码:%s, 角色名:%s; ",
                                e.getUserId(), e.getUserName(), e.getRegionId(),
                                e.getRoleCode(), e.getRoleName()));
                    }
                    if (sb.length() == 0) {
                        return "该设备所在区域没有可用维修员（roleCode=1003）";
                    }
                    return sb.toString();
                })
                .withName("listRepairersByInnerCode")
                .withDescription("专用查询：根据设备编号查询该设备所在区域的维修员列表。参数 innerCode 是设备编号(如VM003)。返回已启用(status=1)且角色为维修员(roleCode=1003)的员工。创建维修工单前必须先调用此工具。")
                .withInputType(InnerCodeInput.class)
                .build();
    }

    private FunctionCallback listBusinessesByInnerCodeCallback() {
        return FunctionCallbackWrapper.builder((Object input) -> {
                    InnerCodeInput req = objectMapper.convertValue(input, InnerCodeInput.class);
                    log.debug("工具函数：根据设备编号 {} 查询运营员", req.getInnerCode());
                    List<EmpDTO> list = dkdClient.listBusinessesByInnerCode(req.getInnerCode());
                    StringBuilder sb = new StringBuilder();
                    for (EmpDTO e : list) {
                        sb.append(String.format("员工ID:%s, 姓名:%s, 区域:%s, 角色编码:%s, 角色名:%s; ",
                                e.getUserId(), e.getUserName(), e.getRegionId(),
                                e.getRoleCode(), e.getRoleName()));
                    }
                    if (sb.length() == 0) {
                        return "该设备所在区域没有可用运营员（roleCode=1002）";
                    }
                    return sb.toString();
                })
                .withName("listBusinessesByInnerCode")
                .withDescription("专用查询：根据设备编号查询该设备所在区域的运营员列表。参数 innerCode 是设备编号(如VM003)。返回已启用(status=1)且角色为运营员(roleCode=1002)的员工。创建投放/撤机工单前必须先调用此工具。")
                .withInputType(InnerCodeInput.class)
                .build();
    }

    private FunctionCallback createRepairTaskCallback() {
        return FunctionCallbackWrapper.builder((Object input) -> {
                    RepairTaskInput req = objectMapper.convertValue(input, RepairTaskInput.class);
                    log.debug("工具函数：创建维修工单 innerCode={}, userId={}, desc={}",
                            req.getInnerCode(), req.getUserId(), req.getDesc());
                    TaskResultDTO result = dkdClient.createRepairTask(
                            req.getInnerCode(), req.getUserId(), req.getDesc());
                    return String.format("工单创建结果：%s，工单编号：%s", result.getMsg(), result.getTaskCode());
                })
                .withName("createRepairTask")
                .withDescription("为指定售货机创建维修工单。触发条件：设备 runningStatus 含故障/卡货/断网/离线/温度异常等关键词。参数要求：innerCode 是设备编号(如VM003)，userId 必须先调用 listRepairersByInnerCode 工具查询后取返回的维修员 userId，desc 为故障描述（如：3号机卡货需维修）。")
                .withInputType(RepairTaskInput.class)
                .build();
    }

    private FunctionCallback createSupplyTaskCallback() {
        return FunctionCallbackWrapper.builder((Object input) -> {
                    SupplyTaskInput req = objectMapper.convertValue(input, SupplyTaskInput.class);
                    log.debug("工具函数：创建补货工单 innerCode={}, userId={}, desc={}",
                            req.getInnerCode(), req.getUserId(), req.getDesc());
                    TaskResultDTO result = dkdClient.createSupplyTask(
                            req.getInnerCode(), req.getUserId(), req.getDesc());
                    return String.format("工单创建结果：%s，工单编号：%s", result.getMsg(), result.getTaskCode());
                })
                .withName("createSupplyTask")
                .withDescription("为指定售货机创建补货工单。触发条件：用户明确要求补货，或设备 lastSupplyTime 超过7天，或 runningStatus 含缺货。参数要求：innerCode 是设备编号，userId 必须先调用 listRepairersByInnerCode 工具查询同区域维修员后取返回的 userId（补货用维修员），desc 是补货描述（如：3号机缺货需补货）。")
                .withInputType(SupplyTaskInput.class)
                .build();
    }

    private FunctionCallback createOperateTaskCallback() {
        return FunctionCallbackWrapper.builder((Object input) -> {
                    OperateTaskInput req = objectMapper.convertValue(input, OperateTaskInput.class);
                    log.debug("工具函数：创建运营工单 innerCode={}, userId={}, productType={}",
                            req.getInnerCode(), req.getUserId(), req.getProductType());
                    // DkdContants: 1=投放 4=撤机
                    Integer mappedType = req.getProductType() == 1 ? 1 : 4;
                    TaskResultDTO result = dkdClient.createOperateTask(
                            req.getInnerCode(), req.getUserId(), req.getDesc(), mappedType);
                    return String.format("工单创建结果：%s，工单编号：%s", result.getMsg(), result.getTaskCode());
                })
                .withName("createOperateTask")
                .withDescription("为指定售货机创建运营工单(投放或撤机)。触发条件：用户明确要求投放/撤机。参数要求：innerCode 是设备编号，userId 必须先调用 listBusinessesByInnerCode 工具查询后取返回的运营员 userId，productType 1=投放 2=撤机。")
                .withInputType(OperateTaskInput.class)
                .build();
    }

    private FunctionCallback queryTaskStatusCallback() {
        return FunctionCallbackWrapper.builder((Object input) -> {
                    TaskCodeInput req = objectMapper.convertValue(input, TaskCodeInput.class);
                    log.debug("工具函数：查询工单状态 taskCode={}", req.getTaskCode());
                    TaskStatusDTO task = dkdClient.queryTaskStatusByCode(req.getTaskCode());
                    if (task == null) {
                        return "未找到工单编号 " + req.getTaskCode() + "，可能已被删除或编号错误";
                    }
                    return String.format("工单编号:%s, 状态:%s, 类型:%s, 设备编号:%s, 执行人:%s, 地址:%s, 描述:%s, 创建时间:%s, 更新时间:%s",
                            task.getTaskCode(),
                            task.getTaskStatusName(),
                            task.getProductTypeName(),
                            task.getInnerCode(),
                            task.getUserName(),
                            task.getAddr(),
                            task.getDesc(),
                            task.getCreateTime(),
                            task.getUpdateTime());
                })
                .withName("queryTaskStatus")
                .withDescription("根据工单编号查询工单实时状态。触发场景：用户询问工单进度、工单是否取消、工单是否完成、工单怎么样了等。参数 taskCode 是工单编号(如202608030001)。返回字段包含：taskCode(工单编号)、taskStatusName(状态：待处理/进行中/已取消/已完成)、productTypeName(类型：投放/补货/维修/撤机)、innerCode(设备编号)、userName(执行人)、addr(地址)、desc(描述)、createTime(创建时间)、updateTime(更新时间)。重要：查询工单状态必须调用此工具，不要凭历史记录回答工单状态。")
                .withInputType(TaskCodeInput.class)
                .build();
    }

    public static class EmptyInput {
    }

    public static class IdInput {
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    public static class InnerCodeInput {
        private String innerCode;
        public String getInnerCode() { return innerCode; }
        public void setInnerCode(String innerCode) { this.innerCode = innerCode; }
    }

    public static class RegionIdInput {
        private Long regionId;
        public Long getRegionId() { return regionId; }
        public void setRegionId(Long regionId) { this.regionId = regionId; }
    }

    public static class RepairTaskInput {
        private String innerCode;
        private Long userId;
        private String desc;
        public String getInnerCode() { return innerCode; }
        public void setInnerCode(String innerCode) { this.innerCode = innerCode; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getDesc() { return desc; }
        public void setDesc(String desc) { this.desc = desc; }
    }

    public static class SupplyTaskInput {
        private String innerCode;
        private Long userId;
        private String desc;
        public String getInnerCode() { return innerCode; }
        public void setInnerCode(String innerCode) { this.innerCode = innerCode; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getDesc() { return desc; }
        public void setDesc(String desc) { this.desc = desc; }
    }

    public static class OperateTaskInput {
        private String innerCode;
        private Long userId;
        private String desc;
        private Integer productType;
        public String getInnerCode() { return innerCode; }
        public void setInnerCode(String innerCode) { this.innerCode = innerCode; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getDesc() { return desc; }
        public void setDesc(String desc) { this.desc = desc; }
        public Integer getProductType() { return productType; }
        public void setProductType(Integer productType) { this.productType = productType; }
    }

    public static class TaskCodeInput {
        private String taskCode;
        public String getTaskCode() { return taskCode; }
        public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
    }
}