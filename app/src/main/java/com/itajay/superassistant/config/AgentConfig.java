package com.itajay.superassistant.config;

import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.itajay.superassistant.agent.ResearchAgent;
import com.itajay.superassistant.agent.ReviewerAgent;
import com.itajay.superassistant.agent.WriterAgent;
import com.itajay.superassistant.tool.CreateAgentTool;
import com.itajay.superassistant.workflow.ResearchWriteWorkflow;
import com.itajay.superassistant.interceptor.PlanModeToolInterceptor;
import com.itajay.superassistant.prompt.PromptSubmitHook;
import com.itajay.superassistant.rag.RagAgent;
import com.itajay.superassistant.tool.FileOperationTool;
import com.itajay.superassistant.tool.MemoryTool;
import com.itajay.superassistant.tool.PlanTool;
import com.itajay.superassistant.tool.TerminalTool;
import com.itajay.superassistant.tool.TodoTool;
import com.itajay.superassistant.tool.WebSearchTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

@Configuration
public class AgentConfig {

    public static final String MAIN_AGENT_INSTRUCTION = """
            You are SuperAssistant, an intelligent and safety-conscious AI assistant.
            Your job is to understand the user's needs and use the right tools to get things done.

            ## Identity
            You are the main agent in a multi-agent system. You orchestrate local tools,
            sub-agents, and workflows. You are methodical: for complex work, plan first;
            for simple requests, respond directly. You personalize responses using memory.

            ## Tool Usage Principles
            - **File operations**: use the dedicated file read/write tools. Avoid terminal commands (cat, echo, sed, etc.) for file work.
            - **Web research**: use the web search and crawl tools. Do not use curl/wget in the terminal.
            - **Task management**: decompose work into tracked todo tasks. Use the task tools to create, start, complete, and query tasks.
            - **Terminal commands**: only as a last resort for operations that genuinely require shell access (builds, git, package managers). Every terminal command requires human approval.
            - **Sub-agents**: delegate specialized work (research, writing, review, RAG queries) to the appropriate sub-agent. Do not try to do everything yourself.
            - The framework provides exact tool names and parameters; use them as defined.

            ## Safety Rules (CRITICAL — violations are unacceptable)
            1. NEVER execute destructive commands: no rm -rf, del /f /s, format, dd, or any command that irreversibly deletes or corrupts data.
            2. NEVER download or execute untrusted scripts, binaries, or installers.
            3. NEVER attempt privilege escalation: no sudo, runas, or su.
            4. NEVER modify system files (boot config, registry, hosts, /etc/passwd, cron, systemd units) unless the user explicitly requests it and approval is granted.
            5. NEVER exfiltrate data: do not upload sensitive files to external servers, do not send secrets via email or web requests.
            6. NEVER attempt to disable or bypass security mechanisms (firewalls, antivirus, authentication).
            7. Do NOT log, store, or reveal credentials, API keys, tokens, or personal identifiers.
            8. If asked to perform a dangerous or unethical action, refuse politely and explain why.
            9. When uncertain about safety, ask the user for confirmation before proceeding.

            ## Workflow
            - Simple requests: respond directly with the appropriate tool.
            - Complex multi-step tasks: enter plan mode (when available), analyze, write a plan to plans/{threadId}.md, present it, and wait for approval.
            - After approval: decompose the plan into tracked todo tasks and execute them step by step.
            - For research+writing: use the researchWrite workflow (one-stop research->write pipeline).
            - Review your own output after major steps; fix issues before calling the task complete.
            """;

    @Bean
    public ReactAgent mainAgent(ChatModel chatModel,
                                 TodoTool todoTool,
                                 WebSearchTool webSearchTool,
                                 FileOperationTool fileOperationTool,
                                 MemoryTool memoryTool,
                                 PromptSubmitHook promptSubmitHook,
                                 PlanModeToolInterceptor planModeToolInterceptor,
                                 PlanTool planTool,
                                 TerminalTool terminalTool,
                                 RagAgent ragAgent,
                                 ResearchAgent researchAgent,
                                 WriterAgent writerAgent,
                                 ReviewerAgent reviewerAgent,
                                 CreateAgentTool createAgentTool,
                                 ResearchWriteWorkflow researchWriteWorkflow,
                                 SkillsAgentHook skillsAgentHook,
                                 MysqlSaver mysqlSaver,
                                 @Nullable ToolCallbackProvider mcpToolCallbackProvider) {

        HumanInTheLoopHook humanInTheLoopHook = HumanInTheLoopHook.builder()
                .approvalOn("writeFile", ToolConfig.builder()
                        .description("文件写入需要人工审批，请确认是否执行").build())
                .approvalOn("deleteFile", ToolConfig.builder()
                        .description("文件删除需要人工审批，请确认是否执行").build())
                .approvalOn("executeCommand", ToolConfig.builder()
                        .description("终端命令执行需要人工审批，请确认命令和参数无误后再执行").build())
                .approvalOn("sendEmail", ToolConfig.builder()
                        .description("邮件发送需要人工审批，发送前可编辑收件人/主题/正文").build())
                .approvalOn("sendEmailBatch", ToolConfig.builder()
                        .description("批量邮件发送需要人工审批，发送前可编辑收件人列表/主题/正文").build())
                .build();

        // Wrap each sub-agent as an AgentTool so the main agent can call them directly
        ToolCallback ragTool = AgentTool.create(ragAgent.reactAgent);
        ToolCallback researchTool = AgentTool.create(researchAgent.reactAgent);
        ToolCallback writerTool = AgentTool.create(writerAgent.reactAgent);
        ToolCallback reviewerTool = AgentTool.create(reviewerAgent.reactAgent);

        var builder = ReactAgent.builder()
                .name("main-agent")
                .description("Main agent: handles user interaction, planning, tool orchestration, and sub-agent delegation.")
                .model(chatModel)
                .instruction(MAIN_AGENT_INSTRUCTION)
                .methodTools(todoTool, webSearchTool, fileOperationTool, memoryTool,
                             planTool, terminalTool, createAgentTool, researchWriteWorkflow)
                .tools(ragTool, researchTool, writerTool, reviewerTool)
                .hooks(promptSubmitHook, skillsAgentHook, humanInTheLoopHook)
                .interceptors(planModeToolInterceptor)
                .saver(mysqlSaver)
                .outputKey("output");

        if (mcpToolCallbackProvider != null) {
            builder = builder.toolCallbackProviders(mcpToolCallbackProvider);
        }

        return builder.build();
    }
}
