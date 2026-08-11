## **一个完整压缩例子**

假设当前模型可用窗口接近 180k，当前 messages：

```apl
[
  user("我要学习 queryLoop，按中文解释源码逻辑"),                    // 30 tokens

  assistant("我先读 query.ts"),                                      // 20
  assistant(tool_use id="read_query" name="Read"),                   // 30
  user(tool_result id="read_query", content="query.ts 全文"),        // 70,000

  assistant("我再读 compact.ts"),                                    // 20
  assistant(tool_use id="read_compact" name="Read"),                 // 30
  user(tool_result id="read_compact", content="compact.ts 全文"),    // 50,000

  assistant("我再读 autoCompact.ts"),                                // 20
  assistant(tool_use id="read_auto" name="Read"),                    // 30
  user(tool_result id="read_auto", content="autoCompact.ts 全文"),   // 35,000

  assistant("我解释了 buildQueryConfig、memory prefetch..."),        // 5,000

  user("pendingCacheEdits 是什么？context collapse 和 autocompact 区别？") // 40
]
```

总计约：

```apl
160,220 tokens
```

如果又加上 systemPrompt、tools、userContext，API 实际输入可能超过阈值。

### 第一层：工具结果预算。

判断 `read_query` （某个工具）太大，把它存储/替换成较小内容：类似只拿前多少字节的数据

```apl
user(tool_result id="read_query", content="[Large tool result stored externally. Summary/path...]")
```

变化：

```apl
read_query: 70,000 -> 4,000
```

总计：

```apl
160,220 -> 94,220
```

如果这已经够了，后面不会 autocompact。

如果真实场景更大，预算后仍然：

```apl
175,000
```

### 第二层：snip。没代码

snip 更像 **剪掉旧历史的一些低价值部分**，不是专门只处理工具结果。比如它可能把很旧的交互标记为 snipped，让模型视图过滤掉。假设剪掉旧的解释性 assistant 文本和部分旧附件：

```
175,000 -> 168,000
snip 更偏“历史视图裁剪”；microcompact 更偏“工具结果内容清理/缓存删除”。
```

### 第三层：microcompact。（保留前几个工具结果）

假设 cached microcompact 开启，而且它决定删除旧的两个工具结果缓存：

```apl
toolsToDelete = ["read_query", "read_compact"]
```

它不改本地 messages，只产生：

```apl
pendingCacheEdits = {
  trigger: "auto",
  deletedToolIds: ["read_query", "read_compact"],
  baselineCacheDeletedTokens: 100000
}
```

发给 API 时加：

```apl
cache_edits: [
  { type: "delete", cache_reference: "read_query" },
  { type: "delete", cache_reference: "read_compact" }
]
```

API 返回后：

```apl
cache_deleted_input_tokens = 210000
```

本次删除量：

```apl
210,000 - 100,000 = 110,000 tokens
```

然后 UI/transcript 得到一条：

```apl
Context microcompacted
saved ~110,000 tokens
cleared read_query, read_compact
```

### 第四层：上下文折叠视图。（未实现）

假设 context collapse 开启，它可能把这一段：

更像是  **尽量别动根本结构的折叠视图**

```apl
[
  user("我要学习 queryLoop..."),
  assistant(tool_use read_query),
  user(tool_result query.ts 全文),
  assistant("我解释了 queryLoop 第一部分"),
  assistant(tool_use read_compact),
  user(tool_result compact.ts 全文)
]
```

投影成：

```apl
[
  user("[上下文折叠摘要]
  用户正在学习 Claude Code 的 queryLoop 和 compact 系统。
  已读取 query.ts：queryLoop 会整理上下文、调用模型、执行工具、循环。
  已读取 compact.ts：compactConversation 会生成 compact boundary、summary message、post compact attachments。
  用户强烈要求中文、源码逻辑、具体例子。")
]
```

最近这部分仍保留：

```apl
[
  assistant(tool_use read_auto),
  user(tool_result autoCompact.ts 全文),
  assistant("我解释了 autoCompact..."),
  user("pendingCacheEdits 是什么？")
]
```

所以模型视图可能是：

```apl
[
  user("[上下文折叠摘要] ..."),          // 1,200 tokens
  assistant(tool_use read_auto),        // 30
  user(tool_result autoCompact.ts 全文), // 35,000
  assistant("我解释了 autoCompact..."), // 5,000
  user("pendingCacheEdits 是什么？")     // 40
]
```

这就是折叠视图：旧的 query.ts/compact.ts 原文不再给模型，但最近 autoCompact.ts 原文还保留。

### 第五层：autocompact。

如果前面还不够，autocompact 会走更重的“总结替换”。

比如原文是

```apl
[
  user("我想学习 queryLoop"),

  assistant([
    text("我先读 query.ts"),
    tool_use({ id: "toolu_1", name: "Read", input: { file_path: "src/query.ts" } })
  ]),

  user([
    tool_result({
      tool_use_id: "toolu_1",
      content: "src/query.ts 的完整内容，70000 tokens"
    })
  ]),

  assistant("这里 buildQueryConfig 是..."),

  user("继续看 microcompact"),

  assistant([
    tool_use({ id: "toolu_2", name: "Read", input: { file_path: "src/services/compact/microCompact.ts" } })
  ]),

  user([
    tool_result({
      tool_use_id: "toolu_2",
      content: "microCompact.ts 的完整内容，30000 tokens"
    })
  ]),

  assistant("pendingCacheEdits 的作用是..."),

  user("那 context collapse 和 autocompact 区别是什么？")
]
```

压缩后 messages 变成：

```apl
[
  system(compact_boundary),

  user(isCompactSummary=true, "
    之前用户在学习 Claude Code 的 queryLoop 和 compact。
    已读 query.ts、compact.ts。
    已解释工具执行、memory、skill、context 压缩。
    用户要求中文、逻辑、例子、不要空泛术语。
  "),

  attachment(file, filename="src/compact.ts", content="重新读取后的前 5000 tokens"),
  attachment(file, filename="src/query.ts", content="重新读取后的前 5000 tokens"),

  attachment(invoked_skills, skills=[
    {
      name: "code-review",
      path: ".../SKILL.md",
      content: "skill 文件头部，最多 5000 tokens"
    }
  ]),

  attachment(mcp_instructions_delta, ...当前 MCP 说明...),
  attachment(agent_listing_delta, ...当前 agent 列表...),
  attachment(deferred_tools_delta, ...当前 deferred tools 状态...),

  // 如果有 plan:
  attachment(plan_file_reference, ...),

  // 如果仍在 plan mode:
  attachment(plan_mode, ...),

  // 如果有后台 agent:
  attachment(task_status, ...)
]
```

这里每个东西负责的不是同一件事：

`compact_boundary`：本地边界标记。它告诉系统“从这里开始是压缩后的新历史”。它主要给程序用

`summaryMessages`：旧对话的语义摘要。它替代大量 `user/assistant/tool_result`。摘要提示词会告诉模型  这是从旧会话继续

`file attachment`：恢复最近读过的文件内容。因为摘要里说“读过 query.ts”不等于模型现在还能看到文件原文，所以它最多恢复最近 5 个文件、每个最多 5000 tokens，总预算 50000 tokens

`mcp_instructions_delta`：恢复 MCP 服务说明。压缩会吃掉之前动态插进来的 MCP 说明，所以压缩后重新告诉模型当前 MCP 怎么用。

`agent_listing_delta`：恢复 Agent 工具有哪些可用 agent 类型。比如 `explore`、`plan`。

`plan_file_reference`：如果之前有 plan 文件，压缩后把 plan 内容重新塞回来。否则模型只知道“好像做过计划”，但不知道计划正文。

`plan_mode`：如果当前还在 plan mode，压缩后重新提醒模型“你还处于计划模式”。否则模型可能压缩后忘了不能直接改文件。

`invoked_skills`：如果本轮会话用过 skill，压缩后重新放入 skill 指南。否则摘要只说“用了某 skill”，但模型不知道 skill 的完整规则。

`task_status`：如果有后台 agent 还在跑，或者跑完但结果没取，压缩后重新告诉模型这些任务状态，避免重复开 agent 或忘记去读输出文件。



## 设计思想

摘要负责 **发生过什么**，attachments 负责 **继续工作必须重新拥有的上下文**。如果只保留摘要，模型会知道你读过文件，但看不到文件；知道有计划，但看不到计划；知道有 MCP，但不知道当前 MCP 的说明。这就是为什么压缩后还要追加这么多恢复消息。

如果只有 summary，会有问题：

```scss
summary 说：你读过 src/query.ts。
但模型现在看不到 src/query.ts 的内容。

summary 说：你使用过 code-review skill。
但模型现在不知道这个 skill 的规则。

summary 说：你在 plan mode。
但模型可能忘了 plan mode 下不能直接改文件。

summary 说：有后台 agent 在跑。
但模型可能不知道任务 id、状态、输出文件在哪里。
```

所以 compact 后必须恢复一批 attachment。

### attachment 恢复

#### 1. 最近读过的文件 file attachments

函数：

`./claude-code-main/src/services/compact/compact.ts:createPostCompactFileAttachments`

它做的是：

```scss
从 compact 前的 readFileState 里拿最近读过的文件。
按 timestamp 从新到旧排序。
最多恢复 5 个文件。
每个文件最多 5000 tokens。
全部文件总预算 50000 tokens。
重新读取文件，生成 attachment。
```

配置：

```scss
POST_COMPACT_MAX_FILES_TO_RESTORE = 5
POST_COMPACT_MAX_TOKENS_PER_FILE = 5000
POST_COMPACT_TOKEN_BUDGET = 50000
```

这类 attachment 的意义是：

```scss
summary 只能说“读过这个文件”。
file attachment 让模型压缩后还能看到这个文件的一部分真实内容。
```

但不是所有读过的文件都恢复。

会跳过：

```scss
1. plan 文件，因为 plan 有专门的 plan_file_reference。
2. CLAUDE.md / memory 类文件，因为这些属于用户上下文系统，不应该当普通文件恢复。
3. 如果 preservedMessages 里已经保留了某个 Read 的真实结果，就不重复恢复。
4. 如果 Read 结果只是 file_unchanged stub，会重新恢复真实内容。
```

------

#### 2. 后台 agent / task 状态 task_status

函数：

`./claude-code-main/src/services/compact/compact.ts:createAsyncAgentAttachmentsIfNeeded`

它恢复的是：

**后台 local_agent 任务状态。**

会包含：

```JSON
{
  type: "task_status",
  taskId,
  taskType: "local_agent",
  description,
  status,
  deltaSummary,
  outputFilePath
}
```

为什么要恢复？

因为 compact 会吃掉旧历史。如果旧历史里有：

```scss
模型启动了一个后台 agent。
这个 agent 还在跑，或者跑完但结果还没取。
```

compact 后模型如果不知道这件事，就可能：

```scss
重复开一个 agent；
忘记读取已有 agent 输出；
不知道后台任务还在进行。
```

它会跳过：

```scss
1. 已经 retrieved 的任务。
2. pending 状态任务。
3. 当前 agent 自己。
```

------

#### 3. plan_file_reference

函数：

`./claude-code-main/src/services/compact/compact.ts:createPlanAttachmentIfNeeded`

如果当前 session 有 plan 内容，就恢复：

```json
{
  type: "plan_file_reference",
  planFilePath,
  planContent
}
```

意义：

```scss
summary 说做过计划不够。
模型需要看到计划正文，否则后续执行可能偏离原计划。
```

------

#### 4. plan_mode

函数：

`./claude-code-main/src/services/compact/compact.ts:createPlanModeAttachmentIfNeeded`

如果当前权限模式是 plan：

```scss
appState.toolPermissionContext.mode === "plan"
```

就恢复：

```SJON
{
  type: "plan_mode",
  reminderType: "full",
  isSubAgent,
  planFilePath,
  planExists
}
```

意义：

```scss
compact 后模型必须继续知道：
现在还是 plan mode。
不能突然开始直接 Edit/Write。
```

------

#### 5. invoked_skills

函数：

`./claude-code-main/src/services/compact/compact.ts:createSkillAttachmentIfNeeded`

它恢复的是：

```scss
本 session / 当前 agent 已经真正调用过的 skill 内容。
```

注意，是 invoked skills，不是全部 skills。

它会：

```scss
1. 只取当前 agent 作用域内的 invoked skills。
2. 按最近调用时间排序。
3. 每个 skill 最多 5000 tokens。
4. 总预算 25000 tokens。
5. 保留 skill 文件头部，因为规则通常写在前面。
6. 如果截断，会加提示：需要完整内容可以 Read skill path。
```

为什么一定要保留？

因为 compact 后 summary 可能只剩：

```scss
之前使用过 code-review skill。
```

但模型不知道这个 skill 的完整规则。

而 SkillTool 的 schema 只能告诉模型：

```scss
可以调用 Skill 工具。
```

它不能告诉模型：

```scss
code-review skill 的具体审查标准是什么。
```

所以 `invoked_skills` 是为了让已经启用的规则继续生效。

还有一个关键点：

`./claude-code-main/src/services/compact/postCompactCleanup.ts`

源码明确说不重置 sentSkillNames：

```
不重新注入完整 skill listing。
因为完整 skill 列表很贵，而且收益低。
```

所以 compact 后不是恢复“所有 skill 列表”，而是恢复“已经用过的 skill 正文”。

------

#### 6. deferred_tools_delta

调用位置：

`./claude-code-main/src/services/compact/compact.ts`

compact 会吃掉之前的工具发现 delta。

所以 compact 后重新从当前工具状态生成：

```
deferred_tools_delta
```

意义：

```
如果 ToolSearch / deferred tools 开启，
模型需要知道哪些工具现在已经可见、哪些工具需要按需加载。
```

具体来讲

```typescript
tools: [
  Read,
  Bash,
  ToolSearch
]
```

这只能告诉模型：

```scss
你可以调用 ToolSearch。
```

但模型还不知道 ToolSearch 背后大概有什么东西可搜。于是 `deferred_tools_delta` 会补一句：

```scss
<system-reminder>
The following deferred tools are now available via ToolSearch:
mcp__github__search_issues
mcp__github__read_pull_request
mcp__slack__search
</system-reminder>
```

这不是恢复历史里旧的 delta。

而是：

```scss
根据当前 tools 状态重新宣布一遍。
```

------

#### 7. agent_listing_delta

调用位置：

`./claude-code-main/src/services/compact/compact.ts`

恢复可用 agent 类型列表。

意义：

```scss
compact 前模型可能知道有哪些 agent。
compact 后旧 attachment 被吃掉，所以重新告诉模型当前有哪些 agent 类型可用。
```

比如：

```scss
code-reviewer
explore
planner
```

------

#### 8. mcp_instructions_delta

调用位置：

`./claude-code-main/src/services/compact/compact.ts`

恢复当前 MCP server 的说明。

意义：

```scss
MCP 工具 schema 只能告诉模型“有哪些工具、参数是什么”。
MCP instructions 会告诉模型“这个 server 怎么用、有什么约定”。
```

compact 后旧 MCP instruction attachment 没了，所以要重新按当前 MCP 状态生成。

这里是通过参数设置，这里是动态 MCP 指示，静态是在系统提示词里面，这里也就不需要了。

它们的区别如下

```scss
静态：
当前 MCP 指示直接进入 system prompt。
缺点是 MCP server 连接/断开、instructions 变化时，system prompt 会变化，容易破坏 prompt cache。

动态：
system prompt 保持稳定。
MCP server 当前具体 instructions 通过 messages 尾部动态提醒。
优点是更适合处理中途连接、断开、compact 后重建、减少 system prompt 缓存抖动。
```

