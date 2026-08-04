package com.itajay.superassistant.service;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.itajay.superassistant.entity.PlanStep;
import com.itajay.superassistant.entity.PlanTask;
import com.itajay.superassistant.plan.PlanContextHolder;
import com.itajay.superassistant.plan.PlanProgressNotifier;
import com.itajay.superassistant.plan.PlanStepHook;
import com.itajay.superassistant.security.HITLHelper;
import com.itajay.superassistant.security.PendingInterruptionStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class PlanExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutionService.class);
    private static final int MAX_RETRY = 2;
    private static final int MAX_REVIEW_ROUNDS = 2;
    private static final int PARALLELISM = 4;

    private final SupervisorAgent supervisorAgent;
    private final PlanService planService;
    private final PlanStepService planStepService;
    private final PendingInterruptionStore pendingInterruptionStore;
    private final AgentRunLogService agentRunLogService;
    private final PlanProgressNotifier planProgressNotifier;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(PARALLELISM);
    private final Map<Long, Object> reviewLocks = new ConcurrentHashMap<>();

    public PlanExecutionService(SupervisorAgent supervisorAgent,
                                PlanService planService,
                                PlanStepService planStepService,
                                PendingInterruptionStore pendingInterruptionStore,
                                AgentRunLogService agentRunLogService,
                                PlanProgressNotifier planProgressNotifier,
                                ObjectMapper objectMapper) {
        this.supervisorAgent = supervisorAgent;
        this.planService = planService;
        this.planStepService = planStepService;
        this.pendingInterruptionStore = pendingInterruptionStore;
        this.agentRunLogService = agentRunLogService;
        this.planProgressNotifier = planProgressNotifier;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public Map<String, Object> start(Long planId) {
        PlanTask task = planService.getPlan(planId);
        if (!"APPROVED".equals(task.getStatus())) {
            return error(task, "计划状态不是 APPROVED，无法开始执行: " + task.getStatus());
        }

        task.setStatus("EXECUTING");
        task.setApprovedAt(java.time.LocalDateTime.now());
        planService.updateTask(task);
        broadcast(task);

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
        broadcast(task);
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
                broadcast(task);
                return interruption(task, threadId, metadata);
            }

            String answer = output.state().value("output").map(String::valueOf).orElse(output.toString());
            planStepService.markCompleted(step.getId(), answer);
            broadcast(task);

            ReviewAction reviewAction = handleReviewIfNeeded(task, step, answer);
            if (reviewAction.failure() != null) {
                return reviewAction.failure();
            }
            return executeRemaining(task);
        } catch (Exception e) {
            log.error("Plan resume failed [plan={}, step={}]", planId, step.getId(), e);
            planStepService.markFailed(step.getId(), e.getMessage());
            broadcast(task);
            return error(task, e.getMessage());
        } finally {
            PlanContextHolder.clear();
        }
    }

    private Map<String, Object> executeRemaining(PlanTask task) {
        String threadId = task.getThreadId();
        agentRunLogService.logStart(threadId, task.getId(), null, "plan-executor", task.getObjective());
        try {
            return doExecute(task);
        } catch (Exception e) {
            log.error("Unexpected plan execution failure [plan={}]", task.getId(), e);
            return error(task, e.getMessage() == null ? "Unexpected plan execution failure" : e.getMessage());
        } finally {
            agentRunLogService.logEnd(threadId, task.getId(), null, "plan-executor",
                    task.getResult(), task.getErrorMessage());
        }
    }

    private Map<String, Object> doExecute(PlanTask task) {
        while (true) {
            List<PlanStep> steps = planStepService.getSteps(task.getId());
            Map<Long, Set<Long>> dependencies = buildDependencies(steps);
            Set<Long> completed = steps.stream()
                    .filter(s -> "COMPLETED".equals(s.getStatus()))
                    .map(PlanStep::getId)
                    .collect(Collectors.toSet());

            List<PlanStep> ready = steps.stream()
                    .filter(s -> "PENDING".equals(s.getStatus()))
                    .filter(s -> dependencies.getOrDefault(s.getId(), Set.of())
                            .stream().allMatch(completed::contains))
                    .toList();

            if (ready.isEmpty()) {
                boolean hasPending = steps.stream().anyMatch(s -> "PENDING".equals(s.getStatus()));
                boolean hasFailed = steps.stream().anyMatch(s -> "FAILED".equals(s.getStatus()));
                boolean hasWaiting = planStepService.findWaitingHitl(task.getId()).isPresent();
                if (hasWaiting) {
                    return error(task, "计划存在待审批步骤，请先完成审批");
                }
                if (hasPending) {
                    return error(task, "计划存在未就绪步骤或依赖环");
                }
                if (hasFailed) {
                    return error(task, "计划存在失败步骤");
                }
                break;
            }

            for (PlanStep step : ready) {
                planStepService.markRunning(step.getId());
            }
            broadcast(task);

            List<CompletableFuture<StepResult>> futures = ready.stream()
                    .map(step -> CompletableFuture.supplyAsync(() -> executeStep(task, step), executor))
                    .toList();

            Map<String, Object> interrupted = null;
            Map<String, Object> failed = null;
            boolean reviewInserted = false;

            for (CompletableFuture<StepResult> future : futures) {
                StepResult result;
                try {
                    result = future.join();
                } catch (CompletionException e) {
                    String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    result = StepResult.failed(error(task, message));
                }
                if (result.interrupted()) {
                    interrupted = result.response();
                } else if (result.failed()) {
                    failed = result.response();
                } else {
                    reviewInserted = reviewInserted || result.reviewTriggered();
                }
            }

            if (failed != null) {
                reviewLocks.remove(task.getId());
                return failed;
            }
            if (interrupted != null) {
                reviewLocks.remove(task.getId());
                return interrupted;
            }
            if (reviewInserted) {
                continue;
            }
        }

        task.setResult(latestDeliverableOutput(task.getId()));
        reviewLocks.remove(task.getId());
        task.setStatus("COMPLETED");
        planService.updateTask(task);
        broadcast(task);
        planProgressNotifier.complete(task.getId());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "EXECUTION_COMPLETED");
        response.put("planId", task.getId());
        response.put("threadId", task.getThreadId());
        response.put("result", task.getResult());
        return response;
    }

    private StepResult executeStep(PlanTask task, PlanStep step) {
        String threadId = task.getThreadId();
        int attempt = 0;
        while (true) {
            planStepService.markRunning(step.getId());
            broadcast(task);
            String stepInput = buildStepInput(task, step, planStepService.getSteps(task.getId()).size());
            RunnableConfig config = buildExecutionConfig(threadId, task.getId(), step.getId());
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
                    broadcast(task);
                    return StepResult.interrupted(interruption(task, threadId, metadata));
                }

                String answer = output.state().value("output").map(String::valueOf).orElse(output.toString());
                planStepService.markCompleted(step.getId(), answer);
                broadcast(task);

                ReviewAction reviewAction = handleReviewIfNeeded(task, step, answer);
                if (reviewAction.failure() != null) {
                    return StepResult.failed(reviewAction.failure());
                }
                return StepResult.completed(reviewAction.inserted());
            } catch (Exception e) {
                log.warn("Plan step failed [plan={}, step={}, attempt={}]",
                        task.getId(), step.getId(), attempt + 1, e);
                planStepService.incrementRetry(step.getId());
                if (planStepService.getRetryCount(step.getId()) > MAX_RETRY) {
                    planStepService.markFailed(step.getId(), e.getMessage());
                    broadcast(task);
                    return StepResult.failed(error(task, e.getMessage()));
                }
                attempt++;
            } finally {
                PlanContextHolder.clear();
            }
        }
    }

    private ReviewAction handleReviewIfNeeded(PlanTask task, PlanStep step, String output) {
        if (!"reviewer-agent".equals(step.getAgentName())) {
            return new ReviewAction(false, null);
        }

        Object lock = reviewLocks.computeIfAbsent(task.getId(), k -> new Object());
        synchronized (lock) {
            if (!"REVISE".equalsIgnoreCase(extractVerdict(output))) {
                return new ReviewAction(false, null);
            }

            long revisionCount = planStepService.countByStepKeyPrefix(task.getId(), "review-revision-");
            if (revisionCount >= MAX_REVIEW_ROUNDS) {
                return new ReviewAction(false, error(task,
                        "审查未通过，已超过最大修订次数（" + MAX_REVIEW_ROUNDS + " 轮）"));
            }

            PlanStep target = findTargetWriterStep(task.getId(), step);
            if (target == null) {
                return new ReviewAction(false, error(task, "未找到需要修订的 writer-agent 步骤"));
            }

            String comments = extractComments(output);
            int nextNo = planStepService.maxStepNo(task.getId()) + 1;
            PlanStep writerRevision = planStepService.insertStep(
                    task.getId(),
                    nextNo,
                    "writer-revision-" + nextNo,
                    "writer-agent",
                    "根据审查意见修订：" + (comments.isBlank() ? "请根据上一条审查意见修正" : comments),
                    "解决审查意见中的所有问题",
                    toJson(List.of(target.getId())));
            planStepService.insertStep(
                    task.getId(),
                    nextNo + 1,
                    "review-revision-" + nextNo,
                    "reviewer-agent",
                    "复审修订结果",
                    "确认审查意见已解决，返回 PASS 或 REVISE",
                    toJson(List.of(writerRevision.getId())));
            broadcast(task);
            return new ReviewAction(true, null);
        }
    }

    private PlanStep findTargetWriterStep(Long planId, PlanStep reviewer) {
        return planStepService.getSteps(planId).stream()
                .filter(s -> "writer-agent".equals(s.getAgentName()))
                .filter(s -> "COMPLETED".equals(s.getStatus()))
                .max(Comparator.comparing(PlanStep::getStepNo))
                .orElse(null);
    }

    private Map<Long, Set<Long>> buildDependencies(List<PlanStep> steps) {
        Map<String, Long> idByKey = steps.stream()
                .filter(s -> s.getStepKey() != null)
                .collect(Collectors.toMap(PlanStep::getStepKey, PlanStep::getId, (a, b) -> a));
        Map<Integer, Long> idByStepNo = steps.stream()
                .collect(Collectors.toMap(PlanStep::getStepNo, PlanStep::getId, (a, b) -> a));

        Map<Long, Set<Long>> dependencies = new HashMap<>();
        for (PlanStep step : steps) {
            Set<Long> ids = new HashSet<>();
            for (Object dep : parseDependsOn(step)) {
                if (dep instanceof Number number) {
                    Long id = steps.stream()
                            .filter(s -> Objects.equals(s.getId(), number.longValue()))
                            .map(PlanStep::getId)
                            .findFirst()
                            .orElse(null);
                    if (id == null) {
                        id = idByStepNo.get(number.intValue());
                    }
                    if (id != null) {
                        ids.add(id);
                    }
                } else {
                    Long id = idByKey.get(String.valueOf(dep));
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
            dependencies.put(step.getId(), ids);
        }
        return dependencies;
    }

    private List<Object> parseDependsOn(PlanStep step) {
        if (step.getDependsOn() == null || step.getDependsOn().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(step.getDependsOn(), new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse dependsOn for step {}: {}", step.getId(), step.getDependsOn());
            return List.of();
        }
    }

    private String extractVerdict(String output) {
        try {
            return objectMapper.readTree(extractJson(output)).path("verdict").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String extractComments(String output) {
        try {
            return objectMapper.readTree(extractJson(output)).path("comments").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize plan data", e);
        }
    }

    private RunnableConfig buildExecutionConfig(String threadId, Long planId, Long stepId) {
        RunnableConfig.Builder builder = RunnableConfig.builder()
                .threadId(threadId + ":step:" + stepId)
                .addMetadata(PlanStepHook.MODE_KEY, PlanStepHook.MODE_EXECUTION)
                .addMetadata(PlanStepHook.PLAN_ID_KEY, String.valueOf(planId))
                .addMetadata(PlanStepHook.ORIGINAL_THREAD_ID_KEY, threadId);
        if (stepId != null) {
            builder.addMetadata(PlanStepHook.STEP_ID_KEY, String.valueOf(stepId));
        }
        RunnableConfig config = builder.build();
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

    private String latestDeliverableOutput(Long planId) {
        List<PlanStep> completed = planStepService.getSteps(planId).stream()
                .filter(s -> "COMPLETED".equals(s.getStatus()))
                .toList();
        return completed.stream()
                .filter(s -> !"reviewer-agent".equals(s.getAgentName()))
                .max(Comparator.comparing(PlanStep::getStepNo))
                .map(PlanStep::getOutputSummary)
                .orElseGet(() -> completed.stream()
                        .max(Comparator.comparing(PlanStep::getStepNo))
                        .map(PlanStep::getOutputSummary)
                        .orElse(null));
    }

    private void broadcast(PlanTask task) {
        try {
            planProgressNotifier.broadcast(task.getId(), planService.getPlanResponse(task.getId()));
        } catch (Exception e) {
            log.warn("Failed to broadcast plan progress [plan={}]", task.getId(), e);
        }
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
        broadcast(task);
        planProgressNotifier.complete(task.getId());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "ERROR");
        response.put("planId", task.getId());
        response.put("threadId", task.getThreadId());
        response.put("message", message);
        return response;
    }

    private record StepResult(
            boolean interrupted,
            boolean failed,
            boolean reviewTriggered,
            Map<String, Object> response
    ) {
        static StepResult completed(boolean reviewTriggered) {
            return new StepResult(false, false, reviewTriggered, null);
        }

        static StepResult interrupted(Map<String, Object> response) {
            return new StepResult(true, false, false, response);
        }

        static StepResult failed(Map<String, Object> response) {
            return new StepResult(false, true, false, response);
        }
    }

    private record ReviewAction(boolean inserted, Map<String, Object> failure) {}
}
