package com.itajay.superassistant.app;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.itajay.superassistant.entity.PlanTask;
import com.itajay.superassistant.plan.PlanContextHolder;
import com.itajay.superassistant.security.ApprovalDecision;
import com.itajay.superassistant.security.HITLHelper;
import com.itajay.superassistant.security.PendingInterruptionStore;
import com.itajay.superassistant.service.PlanExecutionService;
import com.itajay.superassistant.service.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class SuperAssistant {

    private static final Logger log = LoggerFactory.getLogger(SuperAssistant.class);

    private final SupervisorAgent supervisorAgent;
    private final PendingInterruptionStore pendingInterruptionStore;
    private final PlanService planService;
    private final PlanExecutionService planExecutionService;

    public SuperAssistant(SupervisorAgent supervisorAgent,
                          PendingInterruptionStore pendingInterruptionStore,
                          PlanService planService,
                          PlanExecutionService planExecutionService) {
        this.supervisorAgent = supervisorAgent;
        this.pendingInterruptionStore = pendingInterruptionStore;
        this.planService = planService;
        this.planExecutionService = planExecutionService;
    }

    @PostMapping("/chat/{threadId}")
    public Map<String, Object> chat(@PathVariable String threadId,
                                    @RequestBody ChatRequest request) {
        log.info("Chat request [thread={}]: {}", threadId, request.message());

        LocalDateTime startedAt = LocalDateTime.now();
        try {
            RunnableConfig config = buildConfig(threadId);
            config.context().put("threadId", threadId);
            PlanContextHolder.setThreadId(threadId);

            Optional<NodeOutput> result = supervisorAgent.invokeAndGetOutput(
                    request.message(), config);

            if (result.isEmpty()) {
                return Map.of("type", "ERROR", "message", "Agent returned empty result");
            }

            NodeOutput output = result.get();

            if (output instanceof InterruptionMetadata metadata) {
                pendingInterruptionStore.put(threadId, config, metadata, request.message(), null, null);
                return interruptionResponse(threadId, metadata);
            }

            Object answer = output.state().value("output").orElse(output.toString());
            pendingInterruptionStore.remove(threadId);

            PlanTask latestPlan = planService.getLatestByThreadId(threadId);
            if (latestPlan != null
                    && "AWAITING_APPROVAL".equals(latestPlan.getStatus())
                    && latestPlan.getCreatedAt() != null
                    && !latestPlan.getCreatedAt().isBefore(startedAt)) {
                return planPendingResponse(latestPlan.getId());
            }

            Long planId = extractPlanId(String.valueOf(answer));
            if (planId != null) {
                return planPendingResponse(planId);
            }

            return Map.of("type", "ANSWER", "threadId", threadId, "response", answer);

        } catch (Exception e) {
            log.error("Chat error [thread={}]", threadId, e);
            pendingInterruptionStore.remove(threadId);
            return Map.of("type", "ERROR", "message", e.getMessage());
        } finally {
            PlanContextHolder.clear();
        }
    }

    @PostMapping("/chat/{threadId}/approve")
    public Map<String, Object> approve(@PathVariable String threadId,
                                       @RequestBody ApproveRequest request) {
        log.info("Approve request [thread={}]: {} decision(s)", threadId,
                request.decisions() != null ? request.decisions().size() : 0);

        PendingInterruptionStore.PendingInterruption pending = pendingInterruptionStore.get(threadId);
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

            if (pending.planId() != null) {
                Map<String, Object> result = planExecutionService.resume(
                        pending.planId(), threadId, resumeConfig, pending.inputMessage());
                if (!"INTERRUPTED".equals(result.get("type"))) {
                    pendingInterruptionStore.remove(threadId);
                }
                return result;
            }

            PlanContextHolder.setThreadId(threadId);
            Optional<NodeOutput> result = supervisorAgent.invokeAndGetOutput(
                    pending.inputMessage(), resumeConfig);

            pendingInterruptionStore.remove(threadId);

            if (result.isEmpty()) {
                return Map.of("type", "ERROR", "message", "Agent 恢复后返回空结果");
            }

            NodeOutput output = result.get();

            if (output instanceof InterruptionMetadata metadata) {
                pendingInterruptionStore.put(threadId, resumeConfig, metadata, pending.inputMessage(), null, null);
                return interruptionResponse(threadId, metadata);
            }

            Object answer = output.state().value("output").orElse(output.toString());
            return Map.of("type", "ANSWER", "threadId", threadId, "response", answer);

        } catch (Exception e) {
            log.error("Approve error [thread={}]", threadId, e);
            pendingInterruptionStore.remove(threadId);
            return Map.of("type", "ERROR", "message", e.getMessage());
        } finally {
            PlanContextHolder.clear();
        }
    }

    private RunnableConfig buildConfig(String threadId) {
        PendingInterruptionStore.PendingInterruption pending = pendingInterruptionStore.get(threadId);
        if (pending != null) {
            return pending.config();
        }
        return RunnableConfig.builder().threadId(threadId).build();
    }

    private Map<String, Object> interruptionResponse(String threadId, InterruptionMetadata metadata) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "INTERRUPTED");
        response.put("threadId", threadId);
        response.put("message", "以下高危操作需要审批");
        response.put("pendingApprovals", HITLHelper.getPendingApprovals(metadata));
        return response;
    }

    private Map<String, Object> planPendingResponse(Long planId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "PLAN_PENDING");
        response.put("planId", planId);
        response.putAll(planService.getPlanResponse(planId));
        return response;
    }

    private Long extractPlanId(String text) {
        int index = text.indexOf("PLAN_PENDING:");
        if (index < 0) {
            return null;
        }
        String rest = text.substring(index + "PLAN_PENDING:".length()).trim();
        int end = 0;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
            end++;
        }
        return end == 0 ? null : Long.valueOf(rest.substring(0, end));
    }

    public record ChatRequest(String message) {}

    public record ApproveRequest(List<ApprovalDecision> decisions) {}
}
