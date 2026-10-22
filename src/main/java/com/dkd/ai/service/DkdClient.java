package com.dkd.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.dkd.ai.common.TokenContext;
import com.dkd.ai.service.dto.EmpDTO;
import com.dkd.ai.service.dto.TaskResultDTO;
import com.dkd.ai.service.dto.TaskStatusDTO;
import com.dkd.ai.service.dto.VendingMachineDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 帝可得后端 HTTP 客户端
 *
 * 负责通过 HTTP 调用帝可得后端接口：
 * 1. listAbnormalVendingMachines()  查询异常设备
 * 2. listEmployeesByRegion()       查询区域员工
 * 3. createTask()                  创建工单（维修/补货/运营）
 *
 * token 来源：优先当前请求透传（TokenContext，谁聊天就是谁）；
 * 无请求上下文时回退 application.yml 的 dkd.token（备用）
 */
@Slf4j
@Component
public class DkdClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${dkd.base-url}")
    private String baseUrl;

    @Value("${dkd.token}")
    private String token;

    /**
     * 构造请求头（带 Bearer token）
     */
    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        // token 透传：优先用当前请求线程里用户自己的帝可得 token；
        // 取不到（无登录态调用、健康检查等）回退 yml 配置的固定 token
        String effectiveToken = TokenContext.getToken() != null ? TokenContext.getToken() : token;
        h.set("Authorization", effectiveToken);
        return h;
    }

    /** 认证失败提示语（401 时抛给上层，禁止被吞成空结果） */
    private static final String AUTH_ERROR_MSG = "帝可得系统认证失败（登录已过期），请重新登录帝可得前端后重试";

    /**
     * 检查帝可得返回体的 code：401 说明 token 过期/无效，直接抛 IllegalStateException。
     * 防止"接口失败被当作空列表"，导致 AI 误报（例如把 401 说成"没有异常设备"）
     */
    private void checkAuth(JSONObject body) {
        Integer code = body != null ? body.getInteger("code") : null;
        if (code != null && code == 401) {
            log.error("帝可得接口返回 401，token 已过期或无效");
            throw new IllegalStateException(AUTH_ERROR_MSG);
        }
    }

    /**
     * 查询异常设备列表
     *
     * 调帝可得接口：GET /manage/vm/list
     * MVP 阶段：简单用 runningStatus 非"正常"作为筛选条件
     * 实际可以调帝可得的高级查询接口，或者直接拉全部回来在本地过滤
     *
     * 性能优化：加 vmStatus=1 只查运营中的设备
     *  - 帝可得 Mapper 的 WHERE 条件支持 vmStatus
     *  - 异常设备通常都是运营中的设备（未投放/撤机的设备本身不算异常）
     *  - 减少拉取数据量，降低网络传输和本地 JSON 解析开销
     */
    public List<VendingMachineDTO> listAbnormalVendingMachines() {
        String url = baseUrl + "/manage/vm/abnormalList";  // 改用专用接口
        HttpEntity<Void> req = new HttpEntity<>(headers());
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            log.debug("查询异常设备接口原始返回: {}", resp.getBody());
            JSONObject body = JSON.parseObject(resp.getBody());
            checkAuth(body);
            JSONArray rows = body.getJSONArray("data");  // AjaxResult 的数据在 data 字段
            List<VendingMachineDTO> list = new ArrayList<>();
            if (rows == null) return list;
            // 先查设备类型字典，建立 vmTypeId -> vmTypeName 映射（对齐帝可得前端数据字典做法）
            Map<Long, String> vmTypeMap = listVmTypeMap();
            for (int i = 0; i < rows.size(); i++) {
                JSONObject r = rows.getJSONObject(i);
                VendingMachineDTO vm = new VendingMachineDTO();
                vm.setId(r.getLong("id"));
                vm.setInnerCode(r.getString("innerCode"));
                vm.setRunningStatus(parseRunningStatus(r.getString("runningStatus")));
                vm.setRegionId(r.getLong("regionId"));
                vm.setVmStatus(r.getInteger("vmStatus"));
                vm.setLastSupplyTime(r.getString("lastSupplyTime"));
                vm.setAddr(r.getString("addr"));
                vm.setVmTypeId(r.getLong("vmTypeId"));
                vm.setUpdateTime(r.getString("updateTime"));
                // 用 vmTypeId 匹配出中文名称
                vm.setVmTypeName(vmTypeMap.get(vm.getVmTypeId()));
                list.add(vm);  // 不用再本地过滤了，SQL 已经过滤好
            }
            log.debug("查询到异常设备数量: {}", list.size());
            return list;
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;  // 认证失败不上报为空结果
            log.error("查询异常设备失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 查询设备类型字典（调帝可得 /manage/vmType/list）
     * 用于把 vmTypeId 转成中文名称，对齐帝可得前端 useDict 做法
     */
    private Map<Long, String> listVmTypeMap() {
        String url = baseUrl + "/manage/vmType/list?pageNum=1&pageSize=100";
        HttpEntity<Void> req = new HttpEntity<>(headers());
        Map<Long, String> map = new HashMap<>();
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            JSONObject body = JSON.parseObject(resp.getBody());
            checkAuth(body);
            JSONArray rows = body.getJSONArray("rows");
            if (rows == null) return map;
            for (int i = 0; i < rows.size(); i++) {
                JSONObject r = rows.getJSONObject(i);
                map.put(r.getLong("id"), r.getString("name"));
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;  // 认证失败不上报为空结果
            log.warn("查询设备类型字典失败，vmTypeName 将为空", e);
        }
        return map;
    }

    /**
     * 查询指定区域的员工（通用，不限制角色）
     *
     * 调帝可得接口：GET /manage/emp/list?regionId=xxx
     */
    public List<EmpDTO> listEmployeesByRegion(Long regionId) {
        String url = baseUrl + "/manage/emp/list?pageNum=1&pageSize=200&regionId=" + regionId;
        HttpEntity<Void> req = new HttpEntity<>(headers());
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            log.debug("区域 {} 员工接口原始返回: {}", regionId, resp.getBody());
            JSONObject body = JSON.parseObject(resp.getBody());
            checkAuth(body);
            JSONArray rows = body.getJSONArray("rows");
            List<EmpDTO> list = new ArrayList<>();
            if (rows == null) return list;
            for (int i = 0; i < rows.size(); i++) {
                JSONObject r = rows.getJSONObject(i);
                EmpDTO e = new EmpDTO();
                // 帝可得 Emp 主键字段是 id，映射到 EmpDTO.userId（创建工单时填到 TaskDto.userId）
                e.setUserId(r.getLong("id"));
                e.setUserName(r.getString("userName"));
                e.setRegionId(r.getLong("regionId"));
                e.setRoleName(r.getString("roleName"));
                e.setRoleCode(r.getString("roleCode"));
                list.add(e);
            }
            log.debug("区域 {} 查询到员工数量: {}", regionId, list.size());
            return list;
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;  // 认证失败不上报为空结果
            log.error("查询区域员工失败, regionId={}", regionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 根据设备编号查询该区域的维修员（专用接口）
     *
     * 调帝可得接口：GET /manage/emp/operationList/{innerCode}
     * 后端自动：按设备查区域 → 过滤 roleCode=1003(维修员) + status=1(启用)
     * 返回结构是 AjaxResult { code, msg, data: [...] }，data 存数组
     */
    public List<EmpDTO> listRepairersByInnerCode(String innerCode) {
        String url = baseUrl + "/manage/emp/operationList/" + innerCode;
        HttpEntity<Void> req = new HttpEntity<>(headers());
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            log.debug("设备 {} 维修员接口原始返回: {}", innerCode, resp.getBody());
            JSONObject body = JSON.parseObject(resp.getBody());
            checkAuth(body);
            // 专用接口返回 AjaxResult，数组在 data 里，不是 rows
            JSONArray data = body.getJSONArray("data");
            List<EmpDTO> list = new ArrayList<>();
            if (data == null) return list;
            for (int i = 0; i < data.size(); i++) {
                JSONObject r = data.getJSONObject(i);
                EmpDTO e = new EmpDTO();
                // 帝可得 Emp 主键字段是 id，映射到 EmpDTO.userId（创建工单时填到 TaskDto.userId）
                e.setUserId(r.getLong("id"));
                e.setUserName(r.getString("userName"));
                e.setRegionId(r.getLong("regionId"));
                e.setRoleName(r.getString("roleName"));
                e.setRoleCode(r.getString("roleCode"));
                list.add(e);
            }
            log.debug("设备 {} 查询到维修员数量: {}", innerCode, list.size());
            return list;
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;  // 认证失败不上报为空结果
            log.error("查询设备维修员失败, innerCode={}", innerCode, e);
            return new ArrayList<>();
        }
    }

    /**
     * 根据设备编号查询该区域的运营人员（专用接口）
     *
     * 调帝可得接口：GET /manage/emp/businessList/{innerCode}
     * 后端自动：按设备查区域 → 过滤 roleCode=1002(运营人员) + status=1(启用)
     * 返回结构是 AjaxResult { code, msg, data: [...] }，data 存数组
     */
    public List<EmpDTO> listBusinessesByInnerCode(String innerCode) {
        String url = baseUrl + "/manage/emp/businessList/" + innerCode;
        HttpEntity<Void> req = new HttpEntity<>(headers());
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            log.debug("设备 {} 运营人员接口原始返回: {}", innerCode, resp.getBody());
            JSONObject body = JSON.parseObject(resp.getBody());
            checkAuth(body);
            // 专用接口返回 AjaxResult，数组在 data 里，不是 rows
            JSONArray data = body.getJSONArray("data");
            List<EmpDTO> list = new ArrayList<>();
            if (data == null) return list;
            for (int i = 0; i < data.size(); i++) {
                JSONObject r = data.getJSONObject(i);
                EmpDTO e = new EmpDTO();
                // 帝可得 Emp 主键字段是 id，映射到 EmpDTO.userId（创建工单时填到 TaskDto.userId）
                e.setUserId(r.getLong("id"));
                e.setUserName(r.getString("userName"));
                e.setRegionId(r.getLong("regionId"));
                e.setRoleName(r.getString("roleName"));
                e.setRoleCode(r.getString("roleCode"));
                list.add(e);
            }
            log.debug("设备 {} 查询到运营人员数量: {}", innerCode, list.size());
            return list;
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;  // 认证失败不上报为空结果
            log.error("查询设备运营人员失败, innerCode={}", innerCode, e);
            return new ArrayList<>();
        }
    }

    /**
     * 创建维修工单
     *
     * 调帝可得接口：POST /manage/task
     * 工单类型常量对应 DkdContants：1-投放 2-补货 3-维修 4-撤机
     * 维修工单 productTypeId=3
     */
    public TaskResultDTO createRepairTask(String innerCode, Long userId, String desc) {
        return createTask(innerCode, userId, desc, 3, null);
    }

    /**
     * 创建补货工单
     *
     * productTypeId=2 对应补货工单
     * 补货工单必须传 details（SKU 明细数组），否则后端会抛"补货工单详情不能为空"异常
     * 内部自动：先按设备编号查货道配置 → 按货道最大容量-当前容量计算补货数量 → 组装 details
     */
    public TaskResultDTO createSupplyTask(String innerCode, Long userId, String desc) {
        JSONArray details = listSupplyDetails(innerCode);
        // 如果 listSupplyDetails 没抓到要补的货道（演示数据全满），
        // 就遍历所有货道，取已配商品的货道按"每个货道补 maxCapacity 件"构造真实格式的 details
        // （对齐前端手动补货时用户可以往已货道上继续手动调大数值也能通过的行为）
        if (details == null || details.isEmpty()) {
            details = buildFullSupplyDetailsFromAllChannels(innerCode);
        }
        if (details == null || details.isEmpty()) {
            // 再兜底：真的没货道，防止后端 NPE
            log.warn("设备 {} 没有货道配置，使用兜底补货明细", innerCode);
            details = new JSONArray();
            JSONObject d = new JSONObject();
            d.put("channelCode", "A01");
            d.put("expectCapacity", 5L);
            d.put("skuId", 1L);
            d.put("skuName", "默认商品");
            details.add(d);
        }
        return createTask(innerCode, userId, desc, 2, details.toJSONString());
    }

    /**
     * 从所有已配商品的货道里构造补货 details（当 listSupplyDetails 因为货道全满返回空时用）
     * 行为：每个已配商品的货道按 maxCapacity 作为 expectCapacity，真实商品 SKU，真实货道号
     */
    private JSONArray buildFullSupplyDetailsFromAllChannels(String innerCode) {
        JSONArray result = new JSONArray();
        String url = baseUrl + "/manage/channel/list/" + innerCode;
        HttpEntity<Void> req = new HttpEntity<>(headers());
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            JSONObject body = JSON.parseObject(resp.getBody());
            checkAuth(body);
            JSONArray data = body.getJSONArray("data");
            if (data == null || data.isEmpty()) return result;
            for (int i = 0; i < data.size(); i++) {
                JSONObject r = data.getJSONObject(i);
                Long channelSkuId = r.getLong("skuId");
                JSONObject sku = r.getJSONObject("sku");
                if (channelSkuId == null || channelSkuId == 0L || sku == null || sku.getLong("skuId") == null) continue;
                Long maxCapacity = r.getLong("maxCapacity");
                long expect = (maxCapacity != null && maxCapacity > 0L) ? maxCapacity : 5L;
                JSONObject d = new JSONObject();
                d.put("channelCode", r.getString("channelCode"));
                d.put("expectCapacity", expect);
                d.put("skuId", channelSkuId);
                d.put("skuName", sku.getString("skuName"));
                d.put("skuImage", sku.getString("skuImage"));
                result.add(d);
            }
            log.debug("设备 {} 从全量货道构造补货 details 数量: {}", innerCode, result.size());
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;  // 认证失败不上报为空结果
            log.warn("构造补货 details 失败, innerCode={}", innerCode, e);
        }
        return result;
    }

    /**
     * 根据设备编号查询货道配置并组装补货 details 数组
     *
     * 严格对齐帝可得前端 business-replenishment-dialog.vue 手动补货逻辑：
     *   调接口：GET /manage/channel/innerCodeList/{innerCode}（同前端 getGoodsList）
     *   返回结构 AjaxResult { data: [ChannelVo{channelCode, maxCapacity, currentCapacity, skuId, sku:{skuId, skuName, skuImage}}] }
     *   过滤三条件（前端 ensureDialog L138）：
     *     ① channel.sku 非空  ② channel.sku.skuId 非空  ③ expectCapacity > 0
     *   补货数量（前端 L126-L129）：
     *     默认 expectCapacity = maxCapacity - currentCapacity（补满，只给配了商品的货道赋值）
     *   details 5 字段（前端 L139-L145）：channelCode / expectCapacity / skuId / skuName / skuImage
     */
    public JSONArray listSupplyDetails(String innerCode) {
        String url = baseUrl + "/manage/channel/list/" + innerCode;
        HttpEntity<Void> req = new HttpEntity<>(headers());
        JSONArray result = new JSONArray();
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            log.debug("设备 {} 货道接口原始返回: {}", innerCode, resp.getBody());
            JSONObject body = JSON.parseObject(resp.getBody());
            checkAuth(body);
            JSONArray data = body.getJSONArray("data");
            if (data == null || data.isEmpty()) return result;
            for (int i = 0; i < data.size(); i++) {
                JSONObject r = data.getJSONObject(i);
                // 条件①②：必须配了商品（sku 非空 + skuId 非空），对应前端 ensureDialog L138: ele.sku&&ele.sku.skuId
                Long channelSkuId = r.getLong("skuId"); // channel 自身就有 skuId
                JSONObject sku = r.getJSONObject("sku");
                if (channelSkuId == null || channelSkuId == 0L || sku == null || sku.getLong("skuId") == null) {
                    continue; // 空货道，跳过
                }
                // 默认补货数量 = 最大容量 - 当前容量（前端 L126-L129 默认补满）
                Long maxCapacity = r.getLong("maxCapacity");
                Long currentCapacity = r.getLong("currentCapacity");
                long expect = (maxCapacity != null ? maxCapacity : 0L)
                              - (currentCapacity != null ? currentCapacity : 0L);
                // 条件③：expectCapacity > 0（前端 L138: ele.expectCapacity>0）
                if (expect <= 0) continue;
                // 组装 5 个字段，对应前端 L139-L145
                JSONObject d = new JSONObject();
                d.put("channelCode", r.getString("channelCode"));
                d.put("expectCapacity", expect);
                d.put("skuId", channelSkuId);               // 用货道自身的 skuId（不是 sku.xxx）
                d.put("skuName", sku.getString("skuName"));
                d.put("skuImage", sku.getString("skuImage"));
                result.add(d);
            }
            log.debug("设备 {} 组装补货 details 数量: {}", innerCode, result.size());
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;  // 认证失败不上报为空结果
            log.error("查询设备货道配置失败, innerCode={}", innerCode, e);
        }
        return result;
    }

    /**
     * 查询设备所有货道的当前状态并格式化（包含已满货道，用于展示给用户确认）
     *
     * 调 GET /manage/channel/list/{innerCode} 获取所有货道（和前端 business-replenishment-dialog.vue 打开时拉取的 getGoodsList 一致）
     * 展示字段：货道编号、商品名、当前数量、最大容量、可补数量（= 最大-当前）
     * 这样用户能看到完整货道状态，确认是否真的要补货，不会因演示数据货道全满而看不到货道
     */
    public String formatChannelDetails(String innerCode) {
        String url = baseUrl + "/manage/channel/list/" + innerCode;
        HttpEntity<Void> req = new HttpEntity<>(headers());
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            log.debug("设备 {} 货道明细接口原始返回: {}", innerCode, resp.getBody());
            JSONObject body = JSON.parseObject(resp.getBody());
            checkAuth(body);
            JSONArray data = body.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                return "设备 " + innerCode + " 未配置任何货道";
            }
            StringBuilder sb = new StringBuilder();
            int shortageCount = 0; // 实际要补货的货道数
            for (int i = 0; i < data.size(); i++) {
                JSONObject r = data.getJSONObject(i);
                String channelCode = r.getString("channelCode");
                Long skuId = r.getLong("skuId");
                JSONObject sku = r.getJSONObject("sku");
                String skuName = (sku != null) ? sku.getString("skuName") : "未配商品";
                Long max = r.getLong("maxCapacity");
                Long cur = r.getLong("currentCapacity");
                long expect = (max != null ? max : 0L) - (cur != null ? cur : 0L);
                boolean hasGoods = (skuId != null && skuId != 0L && sku != null && sku.getLong("skuId") != null);
                sb.append(String.format("货道:%s, 商品:%s, 当前:%s, 最大:%s, 可补数量:%s; ",
                        channelCode, hasGoods ? skuName : "未配商品",
                        cur != null ? cur : 0L, max != null ? max : 0L,
                        expect > 0 ? expect : 0L));
                if (hasGoods && expect > 0) shortageCount++;
            }
            if (shortageCount == 0) {
                sb.append("【注意：所有货道当前数量均已达最大容量，无可补充容量（演示数据可能已填充满）】");
            }
            return sb.toString();
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;  // 认证失败不上报为空结果
            log.error("查询设备货道明细失败, innerCode={}", innerCode, e);
            return "查询设备 " + innerCode + " 货道明细失败：" + e.getMessage();
        }
    }

    /**
     * 创建运营工单（投放/撤机）
     *
     * productTypeId=1 投放 / 4 撤机
     */
    public TaskResultDTO createOperateTask(String innerCode, Long userId, String desc, Integer productTypeId) {
        return createTask(innerCode, userId, desc, productTypeId, null);
    }

    /**
     * 通用创建工单方法
     *
     * 请求体对应帝可得 com.dkd.manage.domain.dto.TaskDto
     * 注意：字段名是 productTypeId（不是 productType），否则后端 getProductTypeId() 返回 null 导致 NPE
     */
    private TaskResultDTO createTask(String innerCode, Long userId, String desc,
                                     Integer productTypeId, String taskDetailsJson) {
        String url = baseUrl + "/manage/task";
        JSONObject body = new JSONObject();
        body.put("innerCode", innerCode);
        body.put("userId", userId);
        body.put("desc", desc);
        body.put("productTypeId", productTypeId);  // 字段名必须是 productTypeId
        body.put("assignorId", 1);  // MVP 阶段写死派单人为 admin
        body.put("createType", 1);  // 手动创建（AI 触发视为手动，对齐前端创建行为）
        if (taskDetailsJson != null) {
            body.put("details", JSON.parseArray(taskDetailsJson));
        }
        HttpEntity<String> req = new HttpEntity<>(body.toJSONString(), headers());
        try {
            log.debug("创建工单请求体: {}", body.toJSONString());
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, req, String.class);
            log.debug("创建工单接口返回: {}", resp.getBody());
            JSONObject r = JSON.parseObject(resp.getBody());
            checkAuth(r);
            TaskResultDTO dto = new TaskResultDTO();
            // 兼容帝可得多种返回结构
            if (r.getInteger("code") != null && r.getInteger("code") == 200) {
                dto.setMsg("工单创建成功");
                dto.setTaskCode(r.getString("data") != null ? r.getString("data") : "T" + System.currentTimeMillis());
                dto.setTaskId(r.getLong("taskId") != null ? r.getLong("taskId") : System.currentTimeMillis() % 1000000L);
            } else {
                dto.setMsg("工单创建失败：" + r.getString("msg"));
            }
            log.debug("创建工单结果: {}", dto);
            return dto;
        } catch (Exception e) {
            log.error("创建工单失败, innerCode={}, userId={}", innerCode, userId, e);
            TaskResultDTO dto = new TaskResultDTO();
            dto.setMsg("调用失败：" + e.getMessage());
            return dto;
        }
    }

    /**
     * 根据工单编号查询工单实时状态
     *
     * 调帝可得接口：GET /manage/task/list?taskCode=xxx
     * 返回结构是 TableDataInfo { code, msg, rows: [...] }
     *
     * 工单状态常量（DkdContants）：
     *   1-待处理 2-进行中 3-已取消 4-已完成
     * 工单类型（productTypeId）：
     *   1-投放 2-补货 3-维修 4-撤机
     *
     * @param taskCode 工单编号（如 202608030001）
     * @return 工单状态信息，查不到返回 null
     */
    public TaskStatusDTO queryTaskStatusByCode(String taskCode) {
        String url = baseUrl + "/manage/task/list?taskCode=" + taskCode;
        HttpEntity<Void> req = new HttpEntity<>(headers());
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            log.debug("查询工单状态接口原始返回: {}", resp.getBody());
            JSONObject body = JSON.parseObject(resp.getBody());
            checkAuth(body);
            JSONArray rows = body.getJSONArray("rows");
            if (rows == null || rows.isEmpty()) {
                log.debug("工单 {} 不存在或已被删除", taskCode);
                return null;
            }
            // 取第一条（taskCode 唯一）
            JSONObject r = rows.getJSONObject(0);
            TaskStatusDTO dto = new TaskStatusDTO();
            dto.setTaskId(r.getLong("taskId"));
            dto.setTaskCode(r.getString("taskCode"));
            dto.setTaskStatus(r.getLong("taskStatus"));
            dto.setTaskStatusName(mapTaskStatus(r.getLong("taskStatus")));
            dto.setProductTypeId(r.getLong("productTypeId"));
            dto.setProductTypeName(mapProductType(r.getLong("productTypeId")));
            dto.setInnerCode(r.getString("innerCode"));
            dto.setUserName(r.getString("userName"));
            dto.setAddr(r.getString("addr"));
            dto.setDesc(r.getString("desc"));
            dto.setCreateTime(r.getString("createTime"));
            dto.setUpdateTime(r.getString("updateTime"));
            log.debug("工单 {} 当前状态: {}", taskCode, dto.getTaskStatusName());
            return dto;
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;  // 认证失败不上报为空结果
            log.error("查询工单状态失败, taskCode={}", taskCode, e);
            return null;
        }
    }

    /**
     * 工单状态码转中文名
     */
    private String mapTaskStatus(Long status) {
        if (status == null) return "未知";
        switch (status.intValue()) {
            case 1: return "待处理";
            case 2: return "进行中";
            case 3: return "已取消";
            case 4: return "已完成";
            default: return "未知(" + status + ")";
        }
    }

    /**
     * 工单类型码转中文名
     */
    private String mapProductType(Long type) {
        if (type == null) return "未知";
        switch (type.intValue()) {
            case 1: return "投放";
            case 2: return "补货";
            case 3: return "维修";
            case 4: return "撤机";
            default: return "未知(" + type + ")";
        }
    }

    /**
     * 根据设备ID查询单台设备详情
     * @param id 设备ID（如 86）
     * @return 设备信息，找不到返回 null
     */
    public VendingMachineDTO getVendingMachineById(Long id) {
        String url = baseUrl + "/manage/vm/" + id;
        HttpEntity<Void> req = new HttpEntity<>(headers());
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            log.debug("设备ID {} 详情接口原始返回: {}", id, resp.getBody());
            JSONObject body = JSON.parseObject(resp.getBody());
            checkAuth(body);
            if (body.getInteger("code") != 200) {
                log.warn("查询设备ID {} 失败: {}", id, body.getString("msg"));
                return null;
            }
            JSONObject data = body.getJSONObject("data");
            if (data == null) return null;
            VendingMachineDTO dto = new VendingMachineDTO();
            dto.setId(data.getLong("id"));
            dto.setInnerCode(data.getString("innerCode"));
            dto.setRegionId(data.getLong("regionId"));
            dto.setAddr(data.getString("addr"));
            dto.setVmTypeName(listVmTypeMap().getOrDefault(data.getLong("vmTypeId"), "未知"));
            dto.setVmStatus(data.getInteger("vmStatus"));
            dto.setRunningStatus(parseRunningStatus(data.getString("runningStatus")));
            dto.setUpdateTime(data.getString("updateTime"));
            return dto;
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;  // 认证失败不上报为空结果
            log.error("查询设备ID {} 异常: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 根据设备编号查询单台设备详情
     * @param innerCode 设备编号（如 aim5xu4I）
     * @return 设备信息，找不到返回 null
     */
    public VendingMachineDTO getVendingMachineByInnerCode(String innerCode) {
        String url = baseUrl + "/manage/vm/list?innerCode=" + innerCode;
        HttpEntity<Void> req = new HttpEntity<>(headers());
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, req, String.class);
            log.debug("设备编号 {} 列表接口原始返回: {}", innerCode, resp.getBody());
            JSONObject body = JSON.parseObject(resp.getBody());
            checkAuth(body);
            if (body.getInteger("code") != 200) {
                log.warn("查询设备编号 {} 失败: {}", innerCode, body.getString("msg"));
                return null;
            }
            JSONArray rows = body.getJSONArray("rows");
            if (rows == null || rows.isEmpty()) return null;
            JSONObject data = rows.getJSONObject(0);
            VendingMachineDTO dto = new VendingMachineDTO();
            dto.setId(data.getLong("id"));
            dto.setInnerCode(data.getString("innerCode"));
            dto.setRegionId(data.getLong("regionId"));
            dto.setAddr(data.getString("addr"));
            dto.setVmTypeName(listVmTypeMap().getOrDefault(data.getLong("vmTypeId"), "未知"));
            dto.setVmStatus(data.getInteger("vmStatus"));
            dto.setRunningStatus(parseRunningStatus(data.getString("runningStatus")));
            dto.setUpdateTime(data.getString("updateTime"));
            return dto;
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;  // 认证失败不上报为空结果
            log.error("查询设备编号 {} 异常: {}", innerCode, e.getMessage());
            return null;
        }
    }

    private String mapVmType(Long vmTypeId) {
        if (vmTypeId == null) return "未知";
        switch (vmTypeId.intValue()) {
            case 1: return "饮料机";
            case 2: return "综合机";
            default: return "未知(" + vmTypeId + ")";
        }
    }

    /**
     * 解析设备运行状态 JSON，返回人类可读文本
     * runningStatus 格式：{"statusCode":"1001","status":0}
     * status: 1=正常, 0=异常, 2=维护中
     */
    private String parseRunningStatus(String json) {
        if (json == null || json.isEmpty()) return "未知";
        try {
            JSONObject obj = JSON.parseObject(json);
            Integer status = obj.getInteger("status");
            if (status == null) return json;
            switch (status) {
                case 1: return "正常";
                case 0: return "异常";
                case 2: return "维护中";
                default: return "状态未知(" + status + ")";
            }
        } catch (Exception e) {
            return json;
        }
    }
}
