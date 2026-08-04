package com.itajay.superassistant.plan;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.itajay.superassistant.entity.PlanStep;
import com.itajay.superassistant.entity.PlanTask;
import com.itajay.superassistant.service.AgentRunLogService;
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
    public static final String STEP_ID_KEY = "stepId";
    public static final String ORIGINAL_THREAD_ID_KEY = "originalThreadId";
    public static final String MODE_EXECUTION = "EXECUTION";

    private final String agentName;
    private final PlanStepService planStepService;
    private final PlanService planService;
    private final AgentRunLogService agentRunLogService;

    public PlanStepHook(String agentName,
                        PlanStepService planStepService,
                        PlanService planService,
                        AgentRunLogService agentRunLogService) {
        this.agentName = agentName;
        this.planStepService = planStepService;
        this.planService = planService;
        this.agentRunLogService = agentRunLogService;
    }

    @Override
    public String getName() {
        return "plan_step_hook_" + agentName;
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        String mode = metadata(config, MODE_KEY);
        String threadId = metadata(config, ORIGINAL_THREAD_ID_KEY);
        if (threadId == null) {
            threadId = config.threadId().orElse(null);
        }
        if (threadId != null) {
            PlanTask latest = planService.getLatestByThreadId(threadId);
            if (latest != null && "AWAITING_APPROVAL".equals(latest.getStatus())) {
                throw new IllegalStateException("存在未批准的计划，禁止提前执行子 Agent: " + agentName);
            }
        }

        Long planId = planId(config);
        Long stepId = stepId(config);
        if (MODE_EXECUTION.equals(mode) && planId != null) {
            if (stepId != null) {
                PlanStep step = planStepService.getStep(stepId);
                if (step == null || !planId.equals(step.getPlanId())
                        || !agentName.equals(step.getAgentName())) {
                    throw new IllegalStateException("Invalid plan step binding: " + stepId);
                }
                planStepService.markRunning(stepId);
            } else {
                Optional<PlanStep> step = planStepService.findNextPending(planId, agentName);
                if (step.isEmpty()) {
                    throw new IllegalStateException("No pending plan step found for agent " + agentName
                            + " in plan " + planId);
                }
                planStepService.markRunning(step.get().getId());
            }
        }

        String input = state.value("input").map(String::valueOf).orElse("");
        agentRunLogService.logStart(threadId, planId, stepId, agentName, input);
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        String threadId = metadata(config, ORIGINAL_THREAD_ID_KEY);
        if (threadId == null) {
            threadId = config.threadId().orElse(null);
        }
        Long planId = planId(config);
        Long stepId = stepId(config);
        String output = state.value("output").map(String::valueOf).orElse("");

        agentRunLogService.logEnd(threadId, planId, stepId, agentName, output, null);

        if (MODE_EXECUTION.equals(metadata(config, MODE_KEY)) && planId != null) {
            if (stepId != null) {
                planStepService.markCompleted(stepId, output);
            } else {
                Optional<PlanStep> step = planStepService.findRunning(planId, agentName);
                step.ifPresent(s -> planStepService.markCompleted(s.getId(), output));
            }
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

    private Long stepId(RunnableConfig config) {
        return config.metadata(STEP_ID_KEY)
                .map(String::valueOf)
                .map(Long::valueOf)
                .orElse(null);
    }
}
