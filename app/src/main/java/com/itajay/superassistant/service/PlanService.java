package com.itajay.superassistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.ObjectMapper;
import com.itajay.superassistant.entity.PlanStep;
import com.itajay.superassistant.entity.PlanTask;
import com.itajay.superassistant.mapper.PlanStepMapper;
import com.itajay.superassistant.mapper.PlanTaskMapper;
import com.itajay.superassistant.plan.PlanDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private static final Set<String> AVAILABLE_AGENTS = Set.of(
            "rag-agent", "research-agent", "writer-agent", "reviewer-agent");

    private static final String PLANNING_SYSTEM_PROMPT = """
            你是一个多智能体协作系统的规划器。你的任务是基于用户目标生成一份可执行的 JSON 计划。

            可用的专业子 Agent：
            - rag-agent：检索知识库中的文档并回答专业问题。
            - research-agent：使用网页搜索、网页抓取和知识库收集资料，输出带来源的调研材料。
            - writer-agent：根据调研材料撰写结构化 Markdown 文档。
            - reviewer-agent：按验收标准审查产出，输出 PASS 或 REVISE 及修改意见。

            规则：
            1. 计划必须只包含 JSON，不要输出 Markdown 代码块以外的内容。
            2. 步骤数量控制在 2-8 个。
            3. 每步必须指定一个 agent。
            4. 使用 dependsOn 表达依赖，没有依赖时使用空数组。
            5. 最后一步必须是 reviewer-agent，用于整体完成审查。
            6. 不要执行任何动作，只生成计划。

            JSON 结构：
            {
              "objective": "用户目标",
              "steps": [
                {"id": "s1", "agent": "research-agent", "goal": "子任务目标", "acceptanceCriteria": "验收标准", "dependsOn": []}
              ]
            }
            """;

    private final ChatClient chatClient;
    private final PlanTaskMapper planTaskMapper;
    private final PlanStepMapper planStepMapper;
    private final ObjectMapper objectMapper;

    public PlanService(ChatClient chatClient,
                       PlanTaskMapper planTaskMapper,
                       PlanStepMapper planStepMapper,
                       ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.planTaskMapper = planTaskMapper;
        this.planStepMapper = planStepMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PlanTask createPlan(String threadId, String objective) {
        String generated = generatePlanJson(objective, null);
        PlanDraft draft = parseAndValidate(generated, objective);

        if (draft.steps().isEmpty()) {
            throw new IllegalArgumentException("Plan contains no steps");
        }

        PlanTask task = new PlanTask();
        task.setThreadId(threadId);
        task.setObjective(draft.objective() != null ? draft.objective() : objective);
        task.setStatus("AWAITING_APPROVAL");
        task.setPlanJson(toJson(draft));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        planTaskMapper.insert(task);

        persistSteps(task, draft);

        log.info("Created plan [id={}, thread={}, steps={}]", task.getId(), threadId, draft.steps().size());
        return task;
    }

    @Transactional
    public PlanTask revisePlan(Long planId, String feedback) {
        PlanTask task = getPlan(planId);
        if (!"REJECTED".equals(task.getStatus())) {
            throw new IllegalArgumentException("计划状态不是 REJECTED，无法修订: " + task.getStatus());
        }

        String revisionInput = task.getObjective() + "\n\n用户修订意见：\n" + feedback;
        PlanDraft draft = parseAndValidate(generatePlanJson(revisionInput, null), revisionInput);

        planStepMapper.delete(
                new LambdaQueryWrapper<PlanStep>().eq(PlanStep::getPlanId, planId));

        task.setPlanJson(toJson(draft));
        task.setStatus("AWAITING_APPROVAL");
        task.setErrorMessage(null);
        task.setResult(null);
        task.setApprovedAt(null);
        task.setUpdatedAt(LocalDateTime.now());
        planTaskMapper.updateById(task);

        persistSteps(task, draft);
        log.info("Revised plan [id={}, steps={}]", planId, draft.steps().size());
        return task;
    }

    private void persistSteps(PlanTask task, PlanDraft draft) {
        int stepNo = 1;
        for (PlanDraft.Step step : draft.steps()) {
            PlanStep entity = new PlanStep();
            entity.setPlanId(task.getId());
            entity.setStepNo(stepNo++);
            entity.setStepKey(step.id());
            entity.setAgentName(step.agent());
            entity.setGoal(step.goal());
            entity.setAcceptanceCriteria(step.acceptanceCriteria());
            entity.setDependsOn(toJson(step.dependsOn() == null ? List.of() : step.dependsOn()));
            entity.setStatus("PENDING");
            entity.setRetryCount(0);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            planStepMapper.insert(entity);
        }
    }

    public PlanTask getPlan(Long planId) {
        PlanTask task = planTaskMapper.selectById(planId);
        if (task == null) {
            throw new IllegalArgumentException("Plan not found: " + planId);
        }
        return task;
    }

    public PlanTask getLatestByThreadId(String threadId) {
        return planTaskMapper.findLatestByThreadId(threadId);
    }

    public List<PlanStep> getSteps(Long planId) {
        return planStepMapper.selectList(
                new LambdaQueryWrapper<PlanStep>()
                        .eq(PlanStep::getPlanId, planId)
                        .orderByAsc(PlanStep::getStepNo));
    }

    public Map<String, Object> getPlanResponse(Long planId) {
        PlanTask task = getPlan(planId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("planId", task.getId());
        response.put("threadId", task.getThreadId());
        response.put("objective", task.getObjective());
        response.put("status", task.getStatus());
        response.put("planJson", task.getPlanJson());
        response.put("steps", getSteps(planId));
        response.put("result", task.getResult());
        response.put("errorMessage", task.getErrorMessage());
        response.put("createdAt", task.getCreatedAt());
        response.put("approvedAt", task.getApprovedAt());
        return response;
    }

    public void updateTask(PlanTask task) {
        task.setUpdatedAt(LocalDateTime.now());
        planTaskMapper.updateById(task);
    }

    private String generatePlanJson(String objective, String previousError) {
        String userPrompt = previousError == null
                ? "用户目标：\n" + objective
                : "用户目标：\n" + objective + "\n\n上一次生成的 JSON 无效：" + previousError + "\n请重新生成。";

        return chatClient.prompt()
                .system(PLANNING_SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();
    }

    private PlanDraft parseAndValidate(String raw, String objective) {
        String clean = extractJson(raw);
        try {
            return validate(objectMapper.readValue(clean, PlanDraft.class));
        } catch (Exception first) {
            log.warn("Plan JSON invalid, retrying: {}", first.getMessage());
            String retry = generatePlanJson(objective, first.getMessage());
            try {
                return validate(objectMapper.readValue(extractJson(retry), PlanDraft.class));
            } catch (Exception second) {
                throw new IllegalArgumentException("Failed to generate a valid plan: " + second.getMessage(), second);
            }
        }
    }

    private PlanDraft validate(PlanDraft draft) {
        if (draft == null || draft.objective() == null || draft.objective().isBlank()) {
            throw new IllegalArgumentException("Plan objective is empty");
        }
        if (draft.steps() == null || draft.steps().isEmpty() || draft.steps().size() > 8) {
            throw new IllegalArgumentException("Plan must contain 1-8 steps");
        }

        Set<String> ids = new HashSet<>();
        for (PlanDraft.Step step : draft.steps()) {
            if (step.id() == null || step.id().isBlank() || !ids.add(step.id())) {
                throw new IllegalArgumentException("Step id missing or duplicated: " + step.id());
            }
            if (!AVAILABLE_AGENTS.contains(step.agent())) {
                throw new IllegalArgumentException("Unknown agent: " + step.agent());
            }
            if (step.goal() == null || step.goal().isBlank()) {
                throw new IllegalArgumentException("Step goal is empty: " + step.id());
            }
            if (step.dependsOn() != null) {
                for (String dep : step.dependsOn()) {
                    if (dep == null || dep.isBlank()) {
                        throw new IllegalArgumentException("Step dependency is empty: " + step.id());
                    }
                    if (!ids.contains(dep)) {
                        throw new IllegalArgumentException("Unknown dependency: " + dep);
                    }
                    if (step.id().equals(dep)) {
                        throw new IllegalArgumentException("Step cannot depend on itself: " + step.id());
                    }
                }
            }
        }

        if (!"reviewer-agent".equals(draft.steps().get(draft.steps().size() - 1).agent())) {
            String lastStepId = draft.steps().get(draft.steps().size() - 1).id();
            List<PlanDraft.Step> steps = new ArrayList<>(draft.steps());
            steps.add(new PlanDraft.Step(
                    "review-final",
                    "reviewer-agent",
                    "对整体产出进行最终审查",
                    "检查完成度、引用来源和格式，返回 PASS 或 REVISE 及修改意见",
                    List.of(lastStepId)));
            draft = new PlanDraft(draft.objective(), steps);
        }
        return draft;
    }

    private String extractJson(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Empty LLM response");
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
}
