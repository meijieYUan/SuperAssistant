package com.itajay.superassistant.plan;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.itajay.superassistant.entity.PlanStep;
import com.itajay.superassistant.entity.PlanTask;
import com.itajay.superassistant.service.PlanService;
import com.itajay.superassistant.service.PlanStepService;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Attached to each professional sub-agent to keep plan_step status in sync
 * with real routing decisions made by the SupervisorAgent.
 */
@HookPositions({HookPosition.BEFORE_AGENT, HookPosition.AFTER_AGENT})
public class PlanStepHook extends AgentHook {

    public static final String MODE_KEY = "planMode";
    public static final String PLAN_ID_KEY = "planId";
    public static final String MODE_PLANNING = "PLANNING";
    public static final String MODE_EXECUTION = "EXECUTION";

    private final String agentName;
    private final PlanStepService planStepService;
    private final PlanService planService;

    public PlanStepHook(String agentName, PlanStepService planStepService, PlanService planService) {
        this.agentName = agentName;
        this.planStepService = planStepService;
        this.planService = planService;
    }

    @Override
    public String getName() {
        return "plan_step_hook_" + agentName;
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        String mode = metadata(config, MODE_KEY);
        if (MODE_PLANNING.equals(mode)) {
            throw new IllegalStateException("Planning mode does not allow sub-agent execution: " + agentName);
        }

        Optional<String> threadId = config.threadId();
        if (threadId.isPresent()) {
            PlanTask latest = planService.getLatestByThreadId(threadId.get());
            if (latest != null && "AWAITING_APPROVAL".equals(latest.getStatus())) {
                throw new IllegalStateException("存在未批准的计划，禁止提前执行子 Agent: " + agentName);
            }
        }

        if (!MODE_EXECUTION.equals(mode)) {
            return CompletableFuture.completedFuture(Map.of());
        }

        Long planId = planId(config);
        if (planId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        Optional<PlanStep> step = planStepService.findNextPending(planId, agentName);
        if (step.isEmpty()) {
            throw new IllegalStateException("No pending plan step found for agent " + agentName
                    + " in plan " + planId);
        }
        planStepService.markRunning(step.get().getId());
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        if (!MODE_EXECUTION.equals(metadata(config, MODE_KEY))) {
            return CompletableFuture.completedFuture(Map.of());
        }

        Long planId = planId(config);
        if (planId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        Optional<PlanStep> step = planStepService.findRunning(planId, agentName);
        if (step.isPresent()) {
            String output = state.value("output").map(String::valueOf).orElse("");
            planStepService.markCompleted(step.get().getId(), output);
        }
        return CompletableFuture.completedFuture(Map.of());
    }

    private String metadata(RunnableConfig config, String key) {
        return config.metadata(key).map(String::valueOf).orElse(null);
    }

    private Long planId(RunnableConfig config) {
        return config.metadata(PLAN_ID_KEY)
                .map(String::valueOf)
                .map(Long::valueOf)
                .orElse(null);
    }
}
