# 帝可得 AI Agent

> 独立的 Spring AI 1.0 Agent 服务，通过 **Function Calling** 让大模型调用售货机业务工具，实现自然语言→真工单全链路。
>
> 工具链对接智谱 GLM 大模型 + dkd-parent 业务系统。

## 技术栈

| 分类 | 技术 |
|------|------|
| 框架 | Spring Boot 3.3 |
| AI | **Spring AI 1.0.0-M3**（Function Calling + ChatClient） |
| 大模型 | 智谱 GLM（GLM-4-Flash） |
| HTTP | 对接 dkd-parent 业务接口（设备/工单/员工） |
| 存储 | Redis（多轮对话历史） |
| 工具 | Maven / Java 17 |

## 核心亮点

### Function Calling 实现思路

```
用户自然语言 ("86号机该维修了")
       │
       ▼
  Spring AI ChatClient（注册 11 个 FunctionCallback 工具函数）
       │
       ▼
  智谱 GLM 大模型自动解析意图 → 生成 tool_call 请求
       │
       ├── 调用 getVendingMachineById(86)                     → 查询设备详情
       ├── 调用 listRepairersByInnerCode("aim5xu4I")          → 匹配维修员
       ├── 调用 createRepairTask(...)                          → 创建维修工单
       └── 调用 queryTaskStatus("T1792673906613")              → 查询工单状态
       │
       ▼
  完整对话返回给用户（全程零代码操作）
```

### 11 个业务工具函数

全部定义在 [DkdTools.java](src/main/java/com/dkd/ai/tool/DkdTools.java)，通过 `dkdFunctionCallbacks()` Bean 注册为 `FunctionCallback[]`，注入 AgentService 后由 ChatClient 的 `.functions()` 加载：

#### 设备查询类（4 个）

| 工具 | 功能 | 输入 |
|------|------|------|
| `getVendingMachineById` | 按设备 ID 查询详情 | `id` |
| `getVendingMachineByInnerCode` | 按设备编号查询详情 | `innerCode` |
| `listAbnormalVendingMachines` | 查询异常设备列表 | 无 |
| `listChannelDetails` | 查询指定设备货道缺货明细 | `innerCode` |

#### 员工查询类（3 个）

| 工具 | 功能 | 输入 |
|------|------|------|
| `listEmployeesByRegion` | 查询指定区域所有员工 | `regionId` |
| `listRepairersByInnerCode` | 按设备编号查维修员（`roleCode=1003`） | `innerCode` |
| `listBusinessesByInnerCode` | 按设备编号查运营员（`roleCode=1002`） | `innerCode` |

#### 工单操作类（4 个）

| 工具 | 功能 | 输入 |
|------|------|------|
| `createRepairTask` | 创建维修工单 | `innerCode, userId, desc` |
| `createSupplyTask` | 创建补货工单 | `innerCode, userId, desc` |
| `createOperateTask` | 创建运营工单（投放 / 撤机） | `innerCode, userId, desc, productType` |
| `queryTaskStatus` | 按工单编号查询状态 | `taskCode` |

### 防 AI 编造工单的后处理校验

AgentService 在拿到 AI 回复后做三道校验，不通过则强制重答（最多 3 次）：

| 校验 | 触发条件 | 重答提示 |
|------|---------|---------|
| 校验1 | 用户要求创建工单但 AI 未调用任何工具 | 必须先调工具获取数据 |
| 校验1.5 | 补货场景未调用 `listChannelDetails` 或 `createSupplyTask` | 补货与运行状态无关，必须查货道 |
| 校验2 | 声称创建工单但未调用 `createXxxTask` | 必须调用创建工单工具，禁止编造工单号 |

通过 `ToolCallContext`（ThreadLocal）记录本次请求实际调用的工具名集合实现。

### Token 透传

前端调 `/ai/chat` 时在 Header 带 `Authorization: Bearer <token>`，AgentController 存入 `TokenContext`（ThreadLocal），DkdClient 调帝可得接口时取出透传，实现"谁聊天就是谁"的身份传递。无请求上下文时回退 `application.yml` 的 `dkd.token`。

## 快速运行

> 运行前需设置环境变量：`ZHIPU_API_KEY`（智谱开放平台申请）、`DKD_TOKEN`（dkd-parent 登录后获取，120 分钟过期）

```bash
# 1. 配置环境变量（或修改 src/main/resources/application.yml）
export ZHIPU_API_KEY=你的智谱key
export DKD_TOKEN=你的帝可得token

# 2. 构建 + 启动（端口 8081）
mvn spring-boot:run

# 3. 调用接口
curl -X POST http://localhost:8081/ai/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $DKD_TOKEN" \
  -d '{"sessionId":"test1","message":"86号机该维修了"}'
```

## 接口列表

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/ai/chat` | AI 对话（带 sessionId 多轮记忆） |
| POST | `/ai/clearHistory` | 清除会话历史 |
| GET | `/ai/health` | 健康检查 |

## 项目结构

```
dkd-ai-agent/
├── src/main/java/com/dkd/ai/
│   ├── AgentApplication.java              # 启动类
│   ├── controller/AgentController.java    # 对外接口（/ai/chat 等）
│   ├── config/AiConfig.java               # ChatClient + System Prompt 配置
│   ├── tool/DkdTools.java                 # ★ 11 个 FunctionCallback 工具函数（亮点）
│   ├── service/AgentService.java          # 对话编排 + 后处理校验
│   ├── service/DkdClient.java             # 对接 dkd-parent 的 HTTP 客户端
│   ├── service/dto/                       # 4 个业务 DTO（Emp/TaskResult/TaskStatus/VendingMachine）
│   └── common/                            # Result / TokenContext / ToolCallContext
└── src/test/java/AgentServiceTest.java
```

## 与 dkd-parent 的关系

本仓库是 AI 增强模块，业务逻辑全部通过 HTTP 调用 dkd-parent 的 REST 接口实现：

```
dkd-ai-agent ──(HTTP)──▶ dkd-parent ──▶ MySQL / Redis
```
