# SuperAssistant —— AI 智能助理平台

基于 Spring AI Alibaba 多智能体架构的企业级 AI 助理，支持对话、RAG 知识检索、待办管理、网页搜索、文件操作、邮件发送，并内置 Human-In-The-Loop 高危操作审批机制。

## 架构概览

```
                        POST /api/chat/{threadId}
                              │
                              ▼
              ┌──────────────────────────────┐
              │        SuperiorAgent          │
              │    (Master Reactive Agent)    │
              │  ┌─────────────────────────┐ │
              │  │  Hooks 拦截链            │ │
              │  │  ├─ SkillsAgentHook     │ │  ← ClasspathSkillRegistry
              │  │  ├─ HumanInTheLoopHook  │ │  ← 高危操作审批中断
              │  │  └─ CustomMessageAgent  │ │  ← 对话记忆注入
              │  │         Hook             │ │
              │  └─────────────────────────┘ │
              │       │ 工具调度              │
              └───────┼──────────────────────┘
                      │
     ┌────────────────┼────────────────────────┐
     ▼                ▼                         ▼
┌─────────┐   ┌─────────────┐    ┌──────────────────────┐
│ RagAgent│   │ Local Tools  │    │  MCP Client (WebFlux) │
│  (子Agent)│   │ ├─ TodoTool  │    │  ──SSE──► Email MCP  │
│         │   │ ├─ WebSearch │    │           Server :8081│
│  RAG 管线│   │ └─ FileOp   │    │  sendEmail            │
└────┬────┘   └─────────────┘    │  sendEmailBatch        │
     │                           └──────────────────────┘
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

## 技术栈

| 类别 | 技术 |
|------|------|
| **语言** | Java 21 |
| **框架** | Spring Boot 4.1 · Spring AI 2.0 · Spring AI Alibaba 2.0-M1.1 |
| **LLM** | DeepSeek (deepseek-chat) |
| **Agent** | ReactAgent + StateGraph + MysqlSaver (checkpoint 持久化) |
| **RAG** | CompressionQueryTransformer · MultiQueryExpander · MilvusVectorStore · ConcatenationDocumentJoiner |
| **向量库** | Milvus 2.x (localhost:9090) |
| **数据库** | MySQL 8.0 (生产) · H2 (开发/测试) |
| **ORM** | MyBatis Plus 3.5.9 |
| **文档解析** | PDF (ParagraphPdfDocumentReader) · Markdown · TXT/Code (TextReader) |
| **工具集成** | MCP (Model Context Protocol) — Client SSE ↔ Server WebMVC |
| **构建** | Maven 多模块 |

## 项目结构

```
SuperAssistant/
├── pom.xml                     # 父 POM，依赖管理
├── app/                        # 主应用模块 :8080
│   ├── pom.xml
│   └── src/main/java/com/itajay/superassistant/
│       ├── SuperAssistantApplication.java
│       ├── app/                # Controller 层
│       │   ├── SuperAssistant.java    # 核心 API：对话 + HITL 审批
│       │   ├── RagController.java     # 知识库文件上传
│       │   └── TodoController.java    # Todo REST API
│       ├── config/             # Spring 配置
│       │   ├── AgentConfig.java       # SuperiorAgent 组装
│       │   ├── ModelConfig.java       # DeepSeek 模型配置
│       │   ├── McpConfig.java         # MCP Client 自动配置
│       │   ├── SaverConfig.java       # 双数据源 (MySQL + rag)
│       │   └── VectorConfig.java      # Milvus 向量库
│       ├── rag/                # RAG 检索增强管线
│       │   ├── RagAgent.java          # 子 Agent：知识检索回答
│       │   ├── RagHook.java           # RAG Hook：压缩→扩展→检索→注入
│       │   ├── QueryTransformation.java  # 查询压缩 + 改写
│       │   ├── QueryExpansion.java    # 多路查询扩展
│       │   ├── DocumentRetrieval.java # Milvus 向量检索
│       │   ├── DoucmentPostRetrieval.java # 结果合并去重
│       │   ├── RagService.java        # 文档导入 + 切分 + 入库
│       │   ├── CustomJdbcChatMemoryRepository.java  # 自定义对话记忆
│       │   └── CustomMessageAgentHook.java  # 记忆注入 Hook
│       ├── security/           # HITL 人工审批
│       │   ├── HITLHelper.java         # 审批决策工具（逐一审批 / 全部 / 编辑参数）
│       │   ├── ApprovalDecision.java   # 审批决策 DTO
│       │   └── PendingApproval.java    # 待审批项 DTO（前端渲染）
│       ├── tool/               # Agent 工具
│       │   ├── TodoTool.java           # 待办 CRUD，多条件组合查询
│       │   ├── WebSearchTool.java      # DuckDuckGo 搜索 + Jsoup 抓取
│       │   └── FileOperationTool.java  # 文件读写/创建/删除/列表
│       ├── service/            # 业务服务层
│       │   ├── TodoService.java
│       │   ├── WebSearchService.java   # HttpClient + Jsoup
│       │   └── FileOperationService.java
│       ├── entity/             # MyBatis Plus 实体
│       │   └── TodoTask.java
│       ├── mapper/             # MyBatis Mapper
│       │   └── TodoTaskMapper.java     # 动态 SQL 组合查询
│       └── skill/              # Agent Skills
│           └── SkillConfig.java        # ClasspathSkillRegistry
│       └── resources/
│           ├── application.yml
│           ├── sql/            # DDL
│           │   ├── schema.sql          # todo_task 表
│           │   └── custom_chat_memory.sql
│           └── skills/         # Skill 定义
│               └── research_writing_skill/
│                   ├── skill.md
│                   ├── template/template.md
│                   └── example/*.png
│
└── server/                     # MCP Email Server :8081
    ├── pom.xml
    └── src/main/java/com/itajay/mcpemail/
        ├── McpEmailServerApplication.java
        └── tool/
            └── EmailMcpTools.java     # sendEmail / sendEmailBatch
```

## 核心功能

### 1. 多智能体协作

- **SupervisorAgent（主 Agent）**：基于 Spring AI Alibaba `SupervisorAgent` 类型实现统一入口和任务路由，可把请求路由到 `rag-agent`、`research-agent`、`writer-agent`、`reviewer-agent`。
- **PlanTool**：只负责把复杂任务拆解成带验收标准的小步骤（计划不含任何执行者信息），返回 `PLAN_PENDING`；用户批准后才开始执行。
- **SupervisorAgent 分配**：批准后所有步骤统一交给 SuperiorAgent，由它决定主 Agent 直接完成或委派专业子 Agent；子 Agent 在运行时认领未分配步骤。
- **并行执行**：执行器按 `dependsOn` 计算可并行步骤，互不依赖的子任务并发执行。
- **审查修订循环**：计划末尾自动追加整体验收任务，由 ReviewerAgent 判断是否完成；返回 `REVISE` 时自动追加“修订 + 复审”步骤，最多修订 2 轮。
- **Agent 运行日志**：`agent_run_log` 记录每次 Agent 运行、计划步骤、输入输出与状态。
- **SSE 进度推送**：`GET /api/plans/{planId}/events` 实时推送计划与步骤状态。
- **Checkpoint**：MysqlSaver 持久化 StateGraph 状态，支持中断恢复。

计划生命周期：

```
用户请求 → SupervisorAgent → PlanTool 拆解任务
       → PLAN_PENDING → 用户批准/拒绝
       → APPROVED → SuperiorAgent 分配并执行步骤 → ReviewAgent 验收
       → REVISE 时自动修订（最多 2 轮）→ COMPLETED / FAILED
```

### 2. RAG 检索增强生成

完整五步管线：

```
用户输入 → CompressionQueryTransformer（结合历史压缩查询）
         → MultiQueryExpander（扩展为 3 路查询，保留原始）
         → MilvusVectorStore（topK=5，相似度≥0.7）
         → ConcatenationDocumentJoiner（去重合并）
         → SystemPrompt 注入上下文
```

- 支持 PDF / Markdown / TXT / Java / Python / XML / JSON 等多格式文档导入
- TokenTextSplitter：chunkSize=800，maxChunks=500
- API：`POST /knowledge/upload`（multipart file）

### 3. Human-In-The-Loop 高危操作审批

**需要审批的工具**（配置在 SuperAssistant 构造函数）：

| 工具 | 审批原因 |
|------|---------|
| `writeFile` | 文件写入 |
| `deleteFile` | 文件删除 |
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
  → 后端 HITLHelper.approveOneByOne() 应用决策
  → RunnableConfig.addHumanFeedback() 注入 → Agent 恢复执行
  → 链式中断自动处理（多个高危操作可连续触发）
```

**Email 编辑审批**：`sendEmail` 的参数是 JSON `{"to","subject","body","isHtml","cc"}`，前端解析后可编辑再提交 `result: "EDITED"` + `editedArguments`。

核心类：[HITLHelper.java](app/src/main/java/com/itajay/superassistant/security/HITLHelper.java) · [ApprovalDecision.java](app/src/main/java/com/itajay/superassistant/security/ApprovalDecision.java)

### 4. 自定义对话记忆

[CustomJdbcChatMemoryRepository](app/src/main/java/com/itajay/superassistant/rag/CustomJdbcChatMemoryRepository.java)：基于 JDBC + Jackson 自研，替代 Spring AI 默认实现。

- 表 `CUSTOM_CHAT_MEMORY`：conversation_id + sequence_id + content(JSON) + type
- 支持 USER / ASSISTANT / SYSTEM / TOOL 四种消息类型
- 额外提供窗口化查询 `findLatestByConversationId(id, limit)` —— SQL 层 LIMIT 避免全量加载
- 提供追加插入 `appendMessage(id, msg)`
- Builder 模式：`CustomJdbcChatMemoryRepository.builder().dataSource(ds).build()`

### 5. Tools 工具集

**TodoTool**
- `queryTodos(status, priority, keyword)` —— MyBatis Plus 动态 SQL 组合查询
- `getPendingTodos()` / `getOverdueTodos()` / `getTodosByPriority()` / `getTodosByAssignee()`
- 表：`todo_task`（id, title, description, status, priority, due_date, assigned_to, tags）

**WebSearchTool**
- `webSearch(query)` —— DuckDuckGo HTML 搜索（无需 API Key），解析 top 8 结果
- `webCrawl(url)` —— Jsoup 抓取全文，自动截断 >8000 字符防 token 溢出

**FileOperationTool**
- `readFile` / `writeFile` / `createFile` / `deleteFile` / `listFiles`
- 路径限制在 workspace 内

**EmailMcpTools**（独立 MCP Server :8081）
- `sendEmail(to, subject, body, isHtml, cc)` —— SMTP 发送
- `sendEmailBatch(recipients, subject, body, isHtml)` —— 批量发送

### 6. Agent Skills

[SkillConfig](app/src/main/java/com/itajay/superassistant/skill/SkillConfig.java) 通过 ClasspathSkillRegistry 加载 skills：

- `research_writing_skill`：科研论文调研与文献综述撰写。自动检索 arXiv / Semantic Scholar / Google Scholar，精读 4-8 篇论文，提取方法框架/创新点/训练目标，按模板生成结构化调研文档。

添加新 skill：在 `resources/skills/` 下创建 `skill.md` + 可选 `template/`、`example/` 目录即可。

## API 参考

### 核心对话

| Method | Path | Request | Response |
|--------|------|---------|----------|
| POST | `/api/chat/{threadId}` | `{"message":"..."}` | `{type:"ANSWER", response:"..."}` 或 `{type:"INTERRUPTED", pendingApprovals:[...]}` |
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

## 数据库表

| 表 | 用途 |
|---|------|
| `todo_task` | 待办事项，MyBatis Plus 管理 |
| `plan_task` | 多智能体执行计划 |
| `plan_step` | 计划步骤与执行状态 |
| `agent_run_log` | Agent 运行日志 |
| `CUSTOM_CHAT_MEMORY` | 自定义对话记忆，JDBC 直连 |
| `SPRING_AI_CHAT_MEMORY_*` | Spring AI 框架自动管理（checkpoint 等） |

## 注意事项

- `SaverConfig` 中配置了两个 DataSource Bean：`dataSource`（MySQL）和 `ragDataSource`（MySQL superassistant_rag 库），后者用于 CustomJdbcChatMemoryRepository
- `HumanInTheLoopHook` 的恢复机制依赖 `RunnableConfig.Builder.addHumanFeedback(InterruptionMetadata)`，不要手动调 `CompiledGraph.updateState()`
- `CustomMessageAgentHook` 当前仍使用 Spring AI 的 `JdbcChatMemoryRepository`，如需切换到自研的 `CustomJdbcChatMemoryRepository` 需手动替换
- MCP email 工具名称必须与 `@Tool` 注解中暴露的名称一致：`sendEmail` / `sendEmailBatch`
