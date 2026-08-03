package com.itajay.superassistant.service;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.itajay.superassistant.entity.PlanStep;
import com.itajay.superassistant.entity.PlanTask;
import com.itajay.superassistant.plan.PlanContextHolder;
import com.itajay.superassistant.plan.PlanStepHook;
import com.itajay.superassistant.security.HITLHelper;
import com.itajay.superassistant.security.PendingInterruptionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PlanExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutionService.class);
    private static final int MAX_RETRY = 2;

    private final SupervisorAgent supervisorAgent;
    private final PlanService planService;
    private final PlanStepService planStepService;
    private final PendingInterruptionStore pendingInterruptionStore;

    public PlanExecutionService(SupervisorAgent supervisorAgent,
                                PlanService planService,
                                PlanStepService planStepService,
                                PendingInterruptionStore pendingInterruptionStore) {
        this.supervisorAgent = supervisorAgent;
        this.planService = planService;
        this.planStepService = planStepService;
        this.pendingInterruptionStore = pendingInterruptionStore;
    }

    public Map<String, Object> start(Long planId) {
        PlanTask task = planService.getPlan(planId);
        if (!"APPROVED".equals(task.getStatus())) {
            return error(task, "计划状态不是 APPROVED，无法开始执行: " + task.getStatus());
        }

        task.setStatus("EXECUTING");
        task.setApprovedAt(java.time.LocalDateTime.now());
        planService.updateTask(task);

        return executeRemaining(task);
    }

    public Map<String, Object> resume(Long planId,
                                      String threadId,
                                      RunnableConfig resumeConfig,
                                      String inputMessage) {
        PlanTask task = planService.getPlan(planId);
        Optional<PlanStep> waiting = planStepService.findWaitingHitl(planId);
        if (waiting.isEmpty()) {
            return executeRemaining(task);
        }

        PlanStep step = waiting.get();
        planStepService.markRunning(step.getId());
        PlanContextHolder.setThreadId(threadId);
        try {
            Optional<NodeOutput> result = supervisorAgent.invokeAndGetOutput(inputMessage, resumeConfig);
            if (result.isEmpty()) {
                throw new IllegalStateException("Agent 恢复执行后返回空结果");
            }

            NodeOutput output = result.get();
            if (output instanceof InterruptionMetadata metadata) {
                planStepService.markWaitingHitl(step.getId());
                pendingInterruptionStore.put(threadId, resumeConfig, metadata, inputMessage, planId, step.getId());
                return interruption(task, threadId, metadata);
            }

            String answer = output.state().value("output").map(String::valueOf).orElse(output.toString());
            planStepService.markCompleted(step.getId(), answer);
            return executeRemaining(task);
        } catch (Exception e) {
            log.error("Plan resume failed [plan={}, step={}]", planId, step.getId(), e);
            planStepService.markFailed(step.getId(), e.getMessage());
            return error(task, e.getMessage());
        } finally {
            PlanContextHolder.clear();
        }
    }

    private Map<String, Object> executeRemaining(PlanTask task) {
        String threadId = task.getThreadId();
        List<PlanStep> steps = planStepService.getSteps(task.getId());
        String lastOutput = null;

        for (PlanStep step : steps) {
            if ("COMPLETED".equals(step.getStatus())) {
                lastOutput = step.getOutputSummary();
                continue;
            }
            if ("WAITING_HITL".equals(step.getStatus()) || "FAILED".equals(step.getStatus())) {
                continue;
            }

            String stepInput = buildStepInput(task, step, steps.size());
            int attempt = 0;
            while (true) {
                planStepService.markRunning(step.getId());
                RunnableConfig config = buildExecutionConfig(threadId, task.getId());
                PlanContextHolder.setThreadId(threadId);
                try {
                    Optional<NodeOutput> result = supervisorAgent.invokeAndGetOutput(stepInput, config);
                    if (result.isEmpty()) {
                        throw new IllegalStateException("Agent 返回空结果");
                    }

                    NodeOutput output = result.get();
                    if (output instanceof InterruptionMetadata metadata) {
                        planStepService.markWaitingHitl(step.getId());
                        pendingInterruptionStore.put(threadId, config, metadata, stepInput, task.getId(), step.getId());
                        return interruption(task, threadId, metadata);
                    }

                    String answer = output.state().value("output").map(String::valueOf).orElse(output.toString());
                    planStepService.markCompleted(step.getId(), answer);
                    lastOutput = answer;
                    break;
                } catch (Exception e) {
                    log.warn("Plan step failed [plan={}, step={}, attempt={}]",
                            task.getId(), step.getId(), attempt + 1, e);
                    planStepService.incrementRetry(step.getId());
                    if (planStepService.getRetryCount(step.getId()) > MAX_RETRY) {
                        planStepService.markFailed(step.getId(), e.getMessage());
                        return error(task, e.getMessage());
                    }
                    attempt++;
                } finally {
                    PlanContextHolder.clear();
                }
            }
        }

        task.setStatus("COMPLETED");
        task.setResult(lastOutput);
        planService.updateTask(task);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "EXECUTION_COMPLETED");
        response.put("planId", task.getId());
        response.put("threadId", threadId);
        response.put("result", lastOutput);
        return response;
    }

    private RunnableConfig buildExecutionConfig(String threadId, Long planId) {
        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata(PlanStepHook.MODE_KEY, PlanStepHook.MODE_EXECUTION)
                .addMetadata(PlanStepHook.PLAN_ID_KEY, String.valueOf(planId))
                .build();
        config.context().put("threadId", threadId);
        return config;
    }

    private String buildStepInput(PlanTask task, PlanStep step, int total) {
        return """
                [已批准计划执行] planId=%d
                计划目标：%s

                当前步骤 %d/%d：
                负责 Agent：%s
                子任务目标：%s
                验收标准：%s

                请严格按计划执行：只调用 %s 完成本步骤，不要重新规划，不要执行其他 Agent 的职责。完成后输出结果。
                """.formatted(
                task.getId(),
                task.getObjective(),
                step.getStepNo(),
                total,
                step.getAgentName(),
                step.getGoal(),
                step.getAcceptanceCriteria() == null ? "无" : step.getAcceptanceCriteria(),
                step.getAgentName());
    }

    private Map<String, Object> interruption(PlanTask task, String threadId, InterruptionMetadata metadata) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "INTERRUPTED");
        response.put("planId", task.getId());
        response.put("threadId", threadId);
        response.put("message", "计划执行遇到高危操作，需要审批");
        response.put("pendingApprovals", HITLHelper.getPendingApprovals(metadata));
        return response;
    }

    private Map<String, Object> error(PlanTask task, String message) {
        task.setStatus("FAILED");
        task.setErrorMessage(message);
        planService.updateTask(task);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "ERROR");
        response.put("planId", task.getId());
        response.put("threadId", task.getThreadId());
        response.put("message", message);
        return response;
    }

}
