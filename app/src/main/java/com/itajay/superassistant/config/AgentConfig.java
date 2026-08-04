package com.itajay.superassistant.config;

import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.itajay.superassistant.agent.ResearchAgent;
import com.itajay.superassistant.agent.ReviewerAgent;
import com.itajay.superassistant.agent.WriterAgent;
import com.itajay.superassistant.rag.MemoryHook;
import com.itajay.superassistant.rag.RagAgent;
import com.itajay.superassistant.tool.FileOperationTool;
import com.itajay.superassistant.tool.MemoryTool;
import com.itajay.superassistant.tool.PlanTool;
import com.itajay.superassistant.tool.TerminalTool;
import com.itajay.superassistant.tool.TodoTool;
import com.itajay.superassistant.tool.WebSearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AgentConfig {

    public static final String MAIN_AGENT_INSTRUCTION = """
            You are the main agent of a Supervisor workflow.

            ## Memory
            You have persistent memory across sessions. At the start of each conversation,
            you receive a memory profile with known facts about the user. Use this context
            to personalize your responses.

            When to call remember():
            - User explicitly states a preference (languages, tools, work style)
            - User mentions an ongoing project or goal
            - User shares personal context relevant to future interactions
            - User corrects or updates previously stored information
            - Any fact that would help you serve them better next time

            When NOT to remember:
            - Transient facts (weather, search results, one-time queries)
            - Conversation-specific details that won't matter later
            - Information the user might not want persisted

            Use listMemories to review what you know. Use recall to search by topic.
            If memory approaches the 50-fact budget, call consolidateMemories to clean up.

            Rules:
            1. Simple tasks: answer directly or use local tools (TodoTool, WebSearchTool, FileOperationTool, MemoryTool, email tools).
            2. Complex multi-step tasks: call createPlan to generate a plan. After the plan is created, reply to the user with the plan and STOP. Do not output a JSON array of agent names, do not delegate sub-agents before approval.
            3. During approved plan execution: follow the approved plan. Route each subtask by outputting a JSON array with the target agent name, e.g. ["research-agent"]. Do not invent agent names. Available agents: rag-agent, research-agent, writer-agent, reviewer-agent.
            4. Never execute a plan before the user approves it.
            """;

    public static final String SUPERVISOR_INSTRUCTION = """
            You are the supervisor router. Available sub-agents: rag-agent, research-agent, writer-agent, reviewer-agent.
            After each sub-agent finishes, choose the next one based on the approved plan. Return only the agent name, or return FINISH when all steps are complete.
            """;

    public static final String SUPERVISOR_SYSTEM_PROMPT = """
            You are a supervisor agent responsible for task routing and completion decisions.
            Break down approved plans into subtasks and delegate them to specialized agents.
            Never start execution before the user approves the plan.
            """;

    @Bean
    public SupervisorAgent supervisorAgent(ChatClient chatClient,
                                           ChatModel chatModel,
                                           TodoTool todoTool,
                                           WebSearchTool webSearchTool,
                                           FileOperationTool fileOperationTool,
                                           MemoryTool memoryTool,
                                           MemoryHook memoryHook,
                                           PlanTool planTool,
                                           TerminalTool terminalTool,
                                           RagAgent ragAgent,
                                           ResearchAgent researchAgent,
                                           WriterAgent writerAgent,
                                           ReviewerAgent reviewerAgent,
                                           SkillsAgentHook skillsAgentHook,
                                           MysqlSaver mysqlSaver,
                                           ToolCallbackProvider mcpToolCallbackProvider) {

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

        ReactAgent mainAgent = ReactAgent.builder()
                .name("main-agent")
                .description("Supervisor main agent for user interaction, local tools, MCP tools and planning.")
                .chatClient(chatClient)
                .instruction(MAIN_AGENT_INSTRUCTION)
                .methodTools(todoTool, webSearchTool, fileOperationTool, memoryTool, planTool, terminalTool)
                .toolCallbackProviders(mcpToolCallbackProvider)
                .hooks(memoryHook, skillsAgentHook, humanInTheLoopHook)
                .saver(mysqlSaver)
                .outputKey("output")
                .build();

        List<Agent> subAgents = List.of(
                ragAgent.reactAgent,
                researchAgent.reactAgent,
                writerAgent.reactAgent,
                reviewerAgent.reactAgent);

        return SupervisorAgent.builder()
                .name("SupervisorAgent")
                .description("Supervisor that routes user requests and approved plans to specialized agents.")
                .model(chatModel)
                .mainAgent(mainAgent)
                .subAgents(subAgents)
                .saver(mysqlSaver)
                .instruction(SUPERVISOR_INSTRUCTION)
                .systemPrompt(SUPERVISOR_SYSTEM_PROMPT)
                .build();
    }
}
