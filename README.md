# SuperAssistant —— AI 智能助理平台

基于 Spring AI Alibaba 多智能体架构的企业级 AI 助理，支持对话、RAG 知识检索、待办管理、网页搜索、文件操作、邮件发送，内置 Human-In-The-Loop 高危操作审批机制与四层上下文压缩系统。

## 架构概览

```
                        POST /api/chat/{threadId}
                              │
                              ▼
              ┌──────────────────────────────────────┐
              │          main-agent (ReactAgent)      │
              │  ┌──────────────────────────────────┐ │
              │  │  Hooks 拦截链 (按顺序执行)         │ │
              │  │  ├─ CompactHook                  │ │  ← 四层上下文压缩
              │  │  ├─ PromptSubmitHook             │ │  ← 提示词预处理
              │  │  ├─ SkillsAgentHook              │ │  ← ClasspathSkillRegistry
              │  │  └─ HumanInTheLoopHook           │ │  ← 高危操作审批中断
              │  └──────────────────────────────────┘ │
              │  PlanModeToolInterceptor (拦截器)     │  ← 计划模式工具管控
              │       │ 工具调度                       │
              └───────┼──────────────────────────────┘
                      │
     ┌────────────────┼──────────────────────────────────┐
     ▼                ▼                                   ▼
┌─────────┐   ┌──────────────────┐    ┌──────────────────────────┐
│子 Agent │   │   Local Tools     │    │   MCP Client (WebFlux)   │
│├─RagAgent│   │ ├─ TodoTool      │    │   ──SSE──► Email MCP    │
│├─Research│   │ ├─ WebSearchTool │    │            Server :8081  │
││  Agent │   │ ├─ FileOpTool    │    │   sendEmail              │
│├─Writer  │   │ ├─ MemoryTool    │    │   sendEmailBatch         │
││  Agent │   │ ├─ PlanTool      │    └──────────────────────────┘
│└─Reviewer│   │ ├─ TerminalTool  │
│   Agent  │   │ ├─ DateTimeTool  │
│          │   │ ├─ WeatherTool   │
│Research- │   │ └─ CreateAgentTool│
│Write     │   └──────────────────┘
│Workflow  │
└────┬────┘
     │ Milvus 向量检索
     ▼
┌──────────────────┐
│  QueryCompression │  ← 结合历史对话压缩查询
│    → QueryExpand  │  ← 多路扩展 (×3)
│   → DocRetrieval  │  ← Milvus topK=5, similarity≥0.7
│   → DocJoin       │  ← ConcatenationDocumentJoiner
│   → SystemPrompt  │  ← 注入上下文到 Agent
└──────────────────┘
```

## 核心功能

### 1. 多智能体协作与计划模式

- **主 Agent（main-agent）**：基于 Spring AI Alibaba ReactAgent 实现统一入口和任务路由，可将子任务委派给 `rag-agent`、`research-agent`、`writer-agent`、`reviewer-agent` 四个专业子 Agent。
- **PlanTool 计划模式**：复杂任务先进入只读计划阶段，Agent 分析需求生成结构化方案写入 `plans/{threadId}.md`。用户审批通过后才退出计划模式，将计划拆解为可追踪的 Todo 任务执行。
- **PlanModeToolInterceptor**：计划模式下自动拦截写操作工具，仅放行只读类工具（搜索、读文件），防止 Agent 在未审批时越权修改。
- **审查修订循环**：计划末尾自动追加整体验收任务，由 ReviewerAgent 判定是否达标。未达标时自动追加"修订 + 复审"步骤，最多修订 2 轮避免无限循环。
- **ResearchWriteWorkflow**：研究 → 写作的顺序流水线，ResearchAgent 搜集素材后 WriterAgent 撰写结构化 Markdown 文档，作为单一 Tool 暴露给主 Agent 调用。
- **SSE 进度推送**：`GET /api/plans/{planId}/events` 实时推送计划与步骤状态。
- **Checkpoint**：MysqlSaver 持久化 StateGraph 状态，支持会话中断后从断点恢复。

计划生命周期：

```
用户请求 → main-agent → PlanTool 拆解任务
       → PLAN_PENDING → 用户批准/拒绝
       → APPROVED → 并行执行步骤 → ReviewerAgent 验收
       → REVISE 时自动修订（最多 2 轮）→ COMPLETED / FAILED
```

### 2. 四层上下文压缩系统

针对 LLM 上下文窗口限制（DeepSeek ~128K），设计四层递进压缩策略，在 `CompactHook` 中作为 Agent 执行前 Hook 自动触发：

```
L1 工具结果截断 (ToolResultTruncator)
    → 单个 tool_result > 40K 字符时截断为 3K 预览 + 外部存储引用
L2 历史裁剪 (ContextSnip)
    → 消息数 > 60 时裁剪低价值填充消息和重复错误信息
L3 缓存清理 (MicroCompact)
    → 保留最近 5 对工具调用/结果，删除旧缓存工具结果
L4 异步 LLM 摘要压缩 (ContextCompactor)
    → Token 超 160K 时触发，LLM 生成对话摘要，同时恢复：
      · 最近读取的 5 个文件片段（每文件 ≤ 5K tokens）
      · 当前计划文件内容
      · 用户记忆档案 (.memory/MEMORY.md)
      · 已启用的 Skill 规则
    → 压缩不阻塞主流程，下次对话时自动注入恢复的上下文
```

设计要点：**摘要负责"发生过什么"，附件负责"继续工作需要的上下文"**。仅保留摘要会导致 Agent 记得读过文件却看不到文件内容、记得有计划却看不到计划正文，因此每类丢失的上下文都有对应的恢复策略。压缩冷却期（5 次调用）防止频繁触发。

### 3. Human-In-The-Loop 高危操作审批

**需要审批的工具**（配置在 AgentConfig）：

| 工具 | 审批原因 |
|------|---------|
| `writeFile` | 文件写入 |
| `deleteFile` | 文件删除 |
| `executeCommand` | 终端命令执行 |
| `sendEmail` | 邮件发送（审批前可编辑收件人/主题/正文） |
| `sendEmailBatch` | 批量邮件发送（审批前可编辑参数） |

**两阶段交互流程**：

```
阶段 1: POST /api/chat/{threadId}
  → Agent 执行到高危操作
  → 返回 { type: "INTERRUPTED", pendingApprovals: [...] }
  → 前端渲染审批面板 [同意] [拒绝] [编辑]

阶段 2: POST /api/chat/{threadId}/approve
  → 提交 decisions: [{toolId, result, editedArguments?}]
  → 后端 HITLHelper.approveOneByOne() 逐条应用决策
  → RunnableConfig.addHumanFeedback() 注入 → Agent 恢复执行
  → 链式中断自动处理（多个高危操作可连续触发审批）
```

`result` 可选值：`APPROVED` / `REJECTED` / `EDITED`。`EDITED` 时必填 `editedArguments` 提供修改后的工具参数。

核心类：[SuperAssistant.java](app/src/main/java/com/itajay/superassistant/app/SuperAssistant.java) · [HITLHelper.java](app/src/main/java/com/itajay/superassistant/security/HITLHelper.java) · [ApprovalDecision.java](app/src/main/java/com/itajay/superassistant/security/ApprovalDecision.java)

### 4. RAG 检索增强生成

完整五步管线：

```
用户输入 → QueryTransformation（结合历史对话压缩查询）
         → QueryExpansion（扩展为 3 路查询，保留原始语义）
         → DocumentRetrieval → MilvusVectorStore（topK=5，相似度≥0.7）
         → DocumentPostRetrieval（结果去重合并）
         → RagHook 将检索结果注入 SystemPrompt
```

- 支持 PDF / Markdown / TXT / Java / Python / XML / JSON 等多格式文档导入
- TokenTextSplitter：chunkSize=800，maxChunks=500
- API：`POST /knowledge/upload`（multipart file）

### 5. 持久记忆体系

MemoryTool 提供 Agent 可调用的记忆管理能力，存储于 `.memory/` 目录：

| 操作 | 说明 |
|------|------|
| `remember` | 记录用户偏好/项目/事件/知识/联系人，按重要性 1-10 分级，自动去重更新 |
| `recall` | 按关键词搜索记忆，按重要性排序返回 |
| `listMemories` | 按类型分组列出全部记忆及容量使用情况 |
| `deleteFact` | 按 ID 删除指定记忆 |
| `consolidateMemories` | 清理低重要性（<4）记忆并重新编号，保持 ≤ 50 条预算 |

上下文压缩后自动恢复用户记忆档案，确保长会话跨轮次不丢失个性化上下文。

### 6. 自定义对话记忆

[CustomJdbcChatMemoryRepository](app/src/main/java/com/itajay/superassistant/rag/CustomJdbcChatMemoryRepository.java)：基于 JDBC 自研，替代 Spring AI 默认实现。

- 表 `CUSTOM_CHAT_MEMORY`：conversation_id + sequence_id(content) + type + timestamp
- 支持 USER / ASSISTANT / SYSTEM / TOOL 四种消息类型
- 窗口化查询 `findLatestByConversationId(id, limit)` —— SQL 层 LIMIT 避免全量加载
- 追加插入 `appendMessage(id, msg)`
- Builder 模式创建实例

### 7. Tools 工具集

**TodoTool** — `queryTodos(status, priority, keyword)` 动态 SQL 组合查询；`getPendingTodos()` / `getOverdueTodos()` / `getTodosByPriority()`

**WebSearchTool** — DuckDuckGo HTML 搜索（无需 API Key），解析 top 8 结果；`webCrawl(url)` Jsoup 抓取全文，>8K 字符自动截断

**FileOperationTool** — `readFile` / `writeFile` / `createFile` / `deleteFile` / `listFiles`，路径限制在 workspace 内

**MemoryTool** — `remember` / `recall` / `listMemories` / `deleteFact` / `consolidateMemories`（见持久记忆体系）

**PlanTool** — `enterPlanMode` / `exitPlanMode`，计划模式的进入与退出控制

**TerminalTool** — `executeCommand`，终端命令执行（纳入 HITL 审批）

**DateTimeTool** — `getCurrentDateTime`，获取当前日期时间

**WeatherTool** — `getWeather`，查询城市天气

**CreateAgentTool** — 动态创建子 Agent 执行独立任务

**EmailMcpTools**（独立 MCP Server :8081）— `sendEmail` / `sendEmailBatch`，SMTP 发送（纳入 HITL 审批）

### 8. Agent Skills

[SkillConfig](app/src/main/java/com/itajay/superassistant/skill/SkillConfig.java) 通过 ClasspathSkillRegistry 加载 skills：

- `research_writing_skill`：科研论文调研与文献综述撰写。自动检索 arXiv / Semantic Scholar / Google Scholar，精读 4-8 篇论文，提取方法框架/创新点/训练目标，按模板生成结构化调研文档。

添加新 skill：在 `resources/skills/` 下创建 `skill.md` + 可选 `template/`、`example/` 目录即可。

## 技术栈

| 类别 | 技术 |
|------|------|
| **语言** | Java 21 |
| **框架** | Spring Boot 4.1 · Spring AI 2.0 · Spring AI Alibaba 2.0-M1.1 |
| **LLM** | DeepSeek (deepseek-chat) |
| **Agent** | ReactAgent + StateGraph + MysqlSaver (checkpoint 持久化) |
| **RAG** | QueryTransformation · QueryExpansion (×3) · MilvusVectorStore · DocumentPostRetrieval |
| **向量库** | Milvus 2.x (localhost:9090) |
| **数据库** | MySQL 8.0 (生产) · H2 (开发/测试) |
| **ORM** | MyBatis Plus 3.5.17 |
| **文档解析** | PDF (ParagraphPdfDocumentReader) · Markdown · TXT/Code (TextReader) |
| **工具集成** | MCP (Model Context Protocol) — Client SSE ↔ Server WebMVC |
| **前端** | Vue 3 + Vite + Lucide Icons |
| **构建** | Maven 多模块 |

## 项目结构

```
SuperAssistant/
├── pom.xml                        # 父 POM，依赖管理
├── docker-compose.yml             # MySQL + Milvus 容器编排
├── frontend/                      # Vue 3 前端 :5173
│   └── src/
│       ├── App.vue
│       └── views/
│           ├── ChatView.vue       # 对话界面（含 HITL 审批面板 + 计划模式开关）
│           ├── KnowledgeView.vue  # 知识库上传管理
│           ├── PlanView.vue       # 多智能体计划进度
│           └── TodoView.vue       # 待办任务管理
├── app/                           # 主应用模块 :8080
│   ├── pom.xml
│   └── src/main/java/com/itajay/superassistant/
│       ├── SuperAssistantApplication.java
│       ├── app/                   # Controller 层
│       │   ├── SuperAssistant.java       # 核心 API：对话 + HITL 审批
│       │   ├── TodoController.java       # Todo REST API
│       │   ├── RagController.java        # 知识库文件上传
│       │   └── HealthController.java     # 健康检查
│       ├── config/                # Spring 配置
│       │   ├── AgentConfig.java          # main-agent 组装 + Hook 注册 + HITL 配置
│       │   ├── ModelConfig.java          # DeepSeek 模型配置
│       │   ├── McpConfig.java            # MCP Client 自动配置
│       │   ├── SaverConfig.java          # 双数据源 (MySQL + rag)
│       │   └── VectorConfig.java         # Milvus 向量库
│       ├── agent/                 # 专业子 Agent
│       │   ├── ResearchAgent.java        # 研究 Agent：搜索 + 素材收集
│       │   ├── WriterAgent.java          # 写作 Agent：结构化文档撰写
│       │   └── ReviewerAgent.java        # 审查 Agent：计划验收与修订判定
│       ├── compact/               # 四层上下文压缩系统
│       │   ├── CompactHook.java          # Agent 前置 Hook：触发压缩判断
│       │   ├── CompactConfig.java        # 压缩阈值与参数配置
│       │   ├── ContextCompactor.java     # L4：异步 LLM 摘要 + 上下文恢复
│       │   ├── ToolResultTruncator.java  # L1：工具结果截断
│       │   ├── ContextSnip.java          # L2：历史低价值消息裁剪
│       │   ├── MicroCompact.java         # L3：旧工具结果缓存清理
│       │   ├── SessionMemory.java        # 压缩前后会话状态管理
│       │   └── FileReadState.java        # 文件读取状态追踪（用于恢复）
│       ├── plan/                  # 计划模式上下文
│       │   ├── PlanModeContext.java      # 计划模式状态管理
│       │   └── PlanContextHolder.java    # ThreadLocal 上下文持有者
│       ├── prompt/                # 提示词处理
│       │   └── PromptSubmitHook.java     # 提示词预处理 Hook
│       ├── interceptor/           # 工具拦截器
│       │   └── PlanModeToolInterceptor.java  # 计划模式工具管控
│       ├── rag/                   # RAG 检索增强管线
│       │   ├── RagAgent.java             # 子 Agent：知识检索回答
│       │   ├── RagHook.java              # RAG Hook：压缩→扩展→检索→注入
│       │   ├── QueryTransformation.java  # 查询压缩 + 改写
│       │   ├── QueryExpansion.java       # 多路查询扩展
│       │   ├── DocumentRetrieval.java    # Milvus 向量检索
│       │   ├── DocumentPostRetrieval.java# 结果合并去重
│       │   ├── RagService.java           # 文档导入 + 切分 + 入库
│       │   ├── CustomJdbcChatMemoryRepository.java  # 自定义对话记忆
│       │   └── CustomMessageAgentHook.java          # 记忆注入 Hook
│       ├── security/              # HITL 人工审批
│       │   ├── HITLHelper.java            # 审批决策工具（逐一/全部/编辑参数）
│       │   ├── ApprovalDecision.java      # 审批决策 DTO
│       │   ├── PendingApproval.java       # 待审批项 DTO（前端渲染）
│       │   └── PendingInterruptionStore.java  # 中断状态持久化
│       ├── tool/                  # Agent 工具（共 10 个）
│       │   ├── TodoTool.java              # 待办 CRUD
│       │   ├── WebSearchTool.java         # DuckDuckGo + Jsoup
│       │   ├── FileOperationTool.java     # 文件读写/创建/删除/列表
│       │   ├── MemoryTool.java            # 持久记忆（remember/recall/list/delete/consolidate）
│       │   ├── PlanTool.java              # 计划模式入口/出口
│       │   ├── TerminalTool.java          # 终端命令执行（HITL 审批）
│       │   ├── DateTimeTool.java          # 当前日期时间
│       │   ├── WeatherTool.java           # 天气查询
│       │   └── CreateAgentTool.java       # 动态创建子 Agent
│       ├── workflow/              # Agent 工作流
│       │   └── ResearchWriteWorkflow.java  # 研究→写作顺序流水线
│       ├── service/               # 业务服务层
│       │   ├── TodoService.java
│       │   ├── WebSearchService.java
│       │   ├── FileOperationService.java
│       │   ├── AgentRunLogService.java
│       │   └── TaskBreakdown.java
│       ├── entity/                # MyBatis Plus 实体
│       │   ├── TodoTask.java
│       │   └── AgentRunLog.java
│       ├── mapper/                # MyBatis Mapper
│       │   ├── TodoTaskMapper.java
│       │   └── AgentRunLogMapper.java
│       ├── skill/                 # Agent Skills
│       │   └── SkillConfig.java           # ClasspathSkillRegistry
│       └── resources/
│           ├── application.yml
│           ├── sql/
│           │   ├── schema.sql             # todo_task 表
│           │   └── custom_chat_memory.sql
│           └── skills/
│               └── research_writing_skill/
│                   ├── skill.md
│                   ├── template/template.md
│                   └── example/*.png
│
└── server/                        # MCP Email Server :8081
    ├── pom.xml
    └── src/main/java/com/itajay/mcpemail/
        ├── McpEmailServerApplication.java
        └── tool/
            └── EmailMcpTools.java        # sendEmail / sendEmailBatch
```

## API 参考

### 核心对话

| Method | Path | Request | Response |
|--------|------|---------|----------|
| POST | `/api/chat/{threadId}` | `{"message":"...", "mode":"Default|PlanMode"}` | `{type:"ANSWER", response:"..."}` 或 `{type:"INTERRUPTED", pendingApprovals:[...]}` |
| POST | `/api/chat/{threadId}/approve` | `{"decisions":[{"toolId","result","description?","editedArguments?"}]}` | 同 chat 响应格式 |

### 知识库

| Method | Path | Request | Response |
|--------|------|---------|----------|
| POST | `/knowledge/upload` | multipart `file` | `{success:true, chunks:12, filename:"..."}` |

### Todo

| Method | Path | Response |
|--------|------|----------|
| GET | `/api/todos` | `List<TodoTask>` |
| GET | `/api/todos/pending` | `List<TodoTask>` |
| GET | `/api/todos/overdue` | `List<TodoTask>` |
| POST | `/api/todos/query` | `{status, priority, keyword}` → `List<TodoTask>` |

### 多智能体计划

| Method | Path | Response |
|--------|------|----------|
| GET | `/api/plans/{planId}` | 计划、步骤与状态 |
| POST | `/api/plans/{planId}/approve` | 批准计划并开始执行 |
| POST | `/api/plans/{planId}/reject` | 拒绝计划，可附原因 |
| POST | `/api/plans/{planId}/revise` | 根据拒绝原因重新生成计划 |
| GET | `/api/plans/{planId}/events` | SSE 实时进度事件流 |
| GET | `/api/plans/{planId}/runs` | Agent 运行日志 |

### MCP Email Server

独立进程，端口 8081，通过 Spring AI MCP SSE 自动暴露工具回调给主应用。

## 配置说明

**环境变量**（必须）：

```bash
DEEPSEEK_API_KEY=sk-xxxxxxxx    # DeepSeek API 密钥
SMTP_PASSWORD=xxxxxxxx          # 邮箱 SMTP 密码
```

**`app/application.yml` 关键配置**：

```yaml
spring.ai.deepseek.api-key: ${DEEPSEEK_API_KEY}
spring.ai.mcp.client.sse.connections.email-server.url: http://localhost:8081
spring.datasource.url: jdbc:mysql://localhost:3306/superAssistant
spring.datasource.username: root
spring.datasource.password: 123456
```

**外依赖启动**：

```
Milvus    → localhost:9090
MySQL     → localhost:3306 (database: superAssistant)
```

也可以直接用项目根目录的 Docker Compose 启动 MySQL + Milvus 及其依赖：

```bash
docker compose up -d
docker compose ps
```

**初始化计划/日志表**：

```bash
mysql -u root -p superassistant < app/src/main/resources/sql/plan.sql
```

## 快速启动

```bash
# 1. 确保 Milvus + MySQL 已启动

# 2. 启动 MCP Email Server
cd server
mvn spring-boot:run    # → :8081

# 3. 启动主应用
cd app
mvn spring-boot:run    # → :8080

# 4. 测试
curl -X POST http://localhost:8080/api/chat/test-001 \
  -H "Content-Type: application/json" \
  -d '{"message":"帮我搜索最新的大模型新闻"}'
```

## HITL 审批流程（开发参考）

审批触发时，`SuperAssistant.chat()` 返回的数据结构：

```json
{
  "type": "INTERRUPTED",
  "threadId": "test-001",
  "message": "以下高危操作需要审批",
  "pendingApprovals": [
    {
      "toolId": "call_abc123",
      "toolName": "sendEmail",
      "arguments": "{\"to\":\"a@x.com\",\"subject\":\"hello\",\"body\":\"world\",\"isHtml\":false,\"cc\":\"\"}",
      "description": "邮件发送需要人工审批，发送前可编辑收件人/主题/正文"
    }
  ]
}
```

前端提交审批：

```json
POST /api/chat/test-001/approve
{
  "decisions": [
    {
      "toolId": "call_abc123",
      "result": "EDITED",
      "editedArguments": "{\"to\":\"b@x.com\",\"subject\":\"修改后的标题\",\"body\":\"新正文\",\"isHtml\":true,\"cc\":\"c@x.com\"}"
    }
  ]
}
```

`result` 可选值：`APPROVED` / `REJECTED` / `EDITED`。当为 `REJECTED` 时可选填 `description` 描述拒绝理由；当为 `EDITED` 时必填 `editedArguments`。

## Database

| 表 | 用途 |
|---|------|
| `todo_task` | 待办事项，MyBatis Plus 管理 |
| `plan_task` | 多智能体执行计划 |
| `plan_step` | 计划步骤与执行状态 |
| `agent_run_log` | Agent 运行日志 |
| `CUSTOM_CHAT_MEMORY` | 自定义对话记忆，JDBC 直连 |
| `SPRING_AI_CHAT_MEMORY_*` | Spring AI 框架自动管理（checkpoint 持久化） |

## 注意事项

- `SaverConfig` 中配置了两个 DataSource Bean：`dataSource`（MySQL superassistant 库）和 `ragDataSource`（MySQL superassistant_rag 库），后者用于 CustomJdbcChatMemoryRepository
- `HumanInTheLoopHook` 的恢复机制依赖 `RunnableConfig.Builder.addHumanFeedback(InterruptionMetadata)`，不要手动调 `CompiledGraph.updateState()`
- `CustomMessageAgentHook` 当前仍使用 Spring AI 的 `JdbcChatMemoryRepository`，如需切换到自研的 `CustomJdbcChatMemoryRepository` 需手动替换
- MCP email 工具名称必须与 `@Tool` 注解中暴露的名称一致：`sendEmail` / `sendEmailBatch`
- `CompactConfig` 压缩冷却期为 5 次调用，`CONTEXT_WARNING_TOKENS` = 120K，`CONTEXT_CRITICAL_TOKENS` = 160K
- `SessionMemory` 压缩后持久化到 `.compact/session_memory/`，下次对话自动注入恢复消息
- `PlanModeToolInterceptor` 在计划模式下自动拦截 `writeFile`、`deleteFile`、`executeCommand` 等写操作，仅放行只读类工具

维护者：[itajay](mailto:author@itajay.com)
