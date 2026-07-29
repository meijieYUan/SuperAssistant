package com.itajay.superassistant.app;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.itajay.superassistant.rag.RagAgent;
import com.itajay.superassistant.security.ApprovalDecision;
import com.itajay.superassistant.security.HITLHelper;
import com.itajay.superassistant.security.PendingApproval;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class SuperAssistant {

    private static final Logger log = LoggerFactory.getLogger(SuperAssistant.class);

    private final ReactAgent superiorAgent;

    private final ConcurrentHashMap<String, PendingInterruption> pendingInterruptions = new ConcurrentHashMap<>();

    public static final String SYSTEM_PROMPT = "";

    public SuperAssistant(ChatModel chatModel, RagAgent ragAgent,
                          SkillsAgentHook skillsAgentHook,
                          MysqlSaver mysqlSaver, McpSyncClient mcpSyncClient) {

        SyncMcpToolCallbackProvider syncMcpToolCallbackProvider = new SyncMcpToolCallbackProvider(mcpSyncClient);

        HumanInTheLoopHook humanInTheLoopHook = HumanInTheLoopHook.builder()
                .approvalOn("writeFile", ToolConfig.builder()
                        .description("文件写入需要人工审批，请确认是否执行").build())
                .approvalOn("deleteFile", ToolConfig.builder()
                        .description("文件删除需要人工审批，请确认是否执行").build())
                .approvalOn("sendEmail", ToolConfig.builder()
                        .description("邮件发送需要人工审批，发送前可编辑收件人/主题/正文").build())
                .approvalOn("sendEmailBatch", ToolConfig.builder()
                        .description("批量邮件发送需要人工审批，发送前可编辑收件人列表/主题/正文").build())
                .build();

        this.superiorAgent = ReactAgent.builder()
                .model(chatModel)
                .tools(AgentTool.getFunctionToolCallback(ragAgent.reactAgent))
                .toolCallbackProviders(syncMcpToolCallbackProvider)
                .hooks(skillsAgentHook, humanInTheLoopHook)
                .systemPrompt(SYSTEM_PROMPT)
                .saver(mysqlSaver)
                .build();
    }

    @PostMapping("/chat/{threadId}")
    public Map<String, Object> chat(@PathVariable String threadId,
                                    @RequestBody ChatRequest request) {
        log.info("Chat request [thread={}]: {}", threadId, request.message());

        try {
            RunnableConfig config = buildConfig(threadId);

            Optional<NodeOutput> result = superiorAgent.invokeAndGetOutput(
                    request.message(), config);

            if (result.isEmpty()) {
                return Map.of("type", "ERROR", "message", "Agent returned empty result");
            }

            NodeOutput output = result.get();

            if (output instanceof InterruptionMetadata metadata) {
                pendingInterruptions.put(threadId,
                        new PendingInterruption(config, metadata, request.message()));

                List<PendingApproval> approvals = HITLHelper.getPendingApprovals(metadata);

                log.info("Interrupted [thread={}], {} tool(s) need approval: {}",
                        threadId, approvals.size(),
                        approvals.stream().map(PendingApproval::toolName).toList());

                return Map.of(
                        "type", "INTERRUPTED",
                        "threadId", threadId,
                        "message", "以下高危操作需要审批",
                        "pendingApprovals", approvals
                );
            }

            Object answer = output.state().value("output").orElse(output.toString());
            pendingInterruptions.remove(threadId);
            return Map.of("type", "ANSWER", "threadId", threadId, "response", answer);

        } catch (Exception e) {
            log.error("Chat error [thread={}]", threadId, e);
            pendingInterruptions.remove(threadId);
            return Map.of("type", "ERROR", "message", e.getMessage());
        }
    }

    @PostMapping("/chat/{threadId}/approve")
    public Map<String, Object> approve(@PathVariable String threadId,
                                       @RequestBody ApproveRequest request) {
        log.info("Approve request [thread={}]: {} decision(s)", threadId,
                request.decisions() != null ? request.decisions().size() : 0);

        PendingInterruption pending = pendingInterruptions.get(threadId);
        if (pending == null) {
            return Map.of("type", "ERROR", "message",
                    "没有待审批的中断请求, threadId=" + threadId);
        }

        try {
            InterruptionMetadata resolved = HITLHelper.approveOneByOne(
                    pending.metadata(), request.decisions());

            RunnableConfig resumeConfig = RunnableConfig.builder(pending.config())
                    .addHumanFeedback(resolved)
                    .build();

            Optional<NodeOutput> result = superiorAgent.invokeAndGetOutput(
                    pending.inputMessage(), resumeConfig);

            pendingInterruptions.remove(threadId);

            if (result.isEmpty()) {
                return Map.of("type", "ERROR", "message", "Agent 恢复后返回空结果");
            }

            NodeOutput output = result.get();

            if (output instanceof InterruptionMetadata metadata) {
                pendingInterruptions.put(threadId,
                        new PendingInterruption(resumeConfig, metadata, pending.inputMessage()));

                List<PendingApproval> approvals = HITLHelper.getPendingApprovals(metadata);
                return Map.of(
                        "type", "INTERRUPTED",
                        "threadId", threadId,
                        "message", "仍有高危操作需要审批",
                        "pendingApprovals", approvals
                );
            }

            Object answer = output.state().value("output").orElse(output.toString());
            return Map.of("type", "ANSWER", "threadId", threadId, "response", answer);

        } catch (Exception e) {
            log.error("Approve error [thread={}]", threadId, e);
            pendingInterruptions.remove(threadId);
            return Map.of("type", "ERROR", "message", e.getMessage());
        }
    }

    private RunnableConfig buildConfig(String threadId) {
        PendingInterruption pending = pendingInterruptions.get(threadId);
        if (pending != null) {
            return pending.config();
        }
        return RunnableConfig.builder().threadId(threadId).build();
    }

    public record ChatRequest(String message) {}

    public record ApproveRequest(List<ApprovalDecision> decisions) {}

    private record PendingInterruption(
            RunnableConfig config,
            InterruptionMetadata metadata,
            String inputMessage
    ) {}
}