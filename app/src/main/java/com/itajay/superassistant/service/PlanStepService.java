package com.itajay.superassistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itajay.superassistant.entity.PlanStep;
import com.itajay.superassistant.mapper.PlanStepMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PlanStepService {

    private final PlanStepMapper planStepMapper;

    public PlanStepService(PlanStepMapper planStepMapper) {
        this.planStepMapper = planStepMapper;
    }

    public List<PlanStep> getSteps(Long planId) {
        return planStepMapper.selectList(
                new LambdaQueryWrapper<PlanStep>()
                        .eq(PlanStep::getPlanId, planId)
                        .orderByAsc(PlanStep::getStepNo));
    }

    public PlanStep getStep(Long stepId) {
        return planStepMapper.selectById(stepId);
    }

    public Optional<PlanStep> findNextPending(Long planId, String agentName) {
        return planStepMapper.selectList(
                        new LambdaQueryWrapper<PlanStep>()
                                .eq(PlanStep::getPlanId, planId)
                                .eq(PlanStep::getAgentName, agentName)
                                .in(PlanStep::getStatus, "PENDING", "RUNNING")
                                .orderByAsc(PlanStep::getStepNo)
                                .last("LIMIT 1"))
                .stream().findFirst();
    }

    public Optional<PlanStep> findRunning(Long planId, String agentName) {
        return planStepMapper.selectList(
                        new LambdaQueryWrapper<PlanStep>()
                                .eq(PlanStep::getPlanId, planId)
                                .eq(PlanStep::getAgentName, agentName)
                                .eq(PlanStep::getStatus, "RUNNING")
                                .orderByDesc(PlanStep::getStepNo)
                                .last("LIMIT 1"))
                .stream().findFirst();
    }

    public Optional<PlanStep> findWaitingHitl(Long planId) {
        return planStepMapper.selectList(
                        new LambdaQueryWrapper<PlanStep>()
                                .eq(PlanStep::getPlanId, planId)
                                .eq(PlanStep::getStatus, "WAITING_HITL")
                                .orderByAsc(PlanStep::getStepNo)
                                .last("LIMIT 1"))
                .stream().findFirst();
    }

    public int maxStepNo(Long planId) {
        return planStepMapper.selectList(
                        new LambdaQueryWrapper<PlanStep>()
                                .eq(PlanStep::getPlanId, planId)
                                .orderByDesc(PlanStep::getStepNo)
                                .last("LIMIT 1"))
                .stream().findFirst()
                .map(PlanStep::getStepNo)
                .orElse(0);
    }

    public long countByStepKeyPrefix(Long planId, String prefix) {
        return planStepMapper.selectCount(
                new LambdaQueryWrapper<PlanStep>()
                        .eq(PlanStep::getPlanId, planId)
                        .likeRight(PlanStep::getStepKey, prefix));
    }

    public PlanStep insertStep(Long planId,
                               int stepNo,
                               String stepKey,
                               String agentName,
                               String goal,
                               String acceptanceCriteria,
                               String dependsOn) {
        PlanStep step = new PlanStep();
        step.setPlanId(planId);
        step.setStepNo(stepNo);
        step.setStepKey(stepKey);
        step.setAgentName(agentName);
        step.setGoal(goal);
        step.setAcceptanceCriteria(acceptanceCriteria);
        step.setDependsOn(dependsOn);
        step.setStatus("PENDING");
        step.setRetryCount(0);
        step.setCreatedAt(LocalDateTime.now());
        step.setUpdatedAt(LocalDateTime.now());
        planStepMapper.insert(step);
        return step;
    }

    public void markRunning(Long stepId) {
        PlanStep step = new PlanStep();
        step.setId(stepId);
        step.setStatus("RUNNING");
        step.setStartedAt(LocalDateTime.now());
        step.setUpdatedAt(LocalDateTime.now());
        planStepMapper.updateById(step);
    }

    public void markCompleted(Long stepId, String outputSummary) {
        PlanStep step = new PlanStep();
        step.setId(stepId);
        step.setStatus("COMPLETED");
        step.setOutputSummary(outputSummary);
        step.setCompletedAt(LocalDateTime.now());
        step.setUpdatedAt(LocalDateTime.now());
        planStepMapper.updateById(step);
    }

    public void markWaitingHitl(Long stepId) {
        PlanStep step = new PlanStep();
        step.setId(stepId);
        step.setStatus("WAITING_HITL");
        step.setUpdatedAt(LocalDateTime.now());
        planStepMapper.updateById(step);
    }

    public void markFailed(Long stepId, String error) {
        PlanStep step = new PlanStep();
        step.setId(stepId);
        step.setStatus("FAILED");
        step.setErrorMessage(error);
        step.setUpdatedAt(LocalDateTime.now());
        planStepMapper.updateById(step);
    }

    public void incrementRetry(Long stepId) {
        PlanStep current = planStepMapper.selectById(stepId);
        PlanStep step = new PlanStep();
        step.setId(stepId);
        step.setRetryCount((current != null && current.getRetryCount() != null ? current.getRetryCount() : 0) + 1);
        step.setUpdatedAt(LocalDateTime.now());
        planStepMapper.updateById(step);
    }

    public int getRetryCount(Long stepId) {
        PlanStep step = planStepMapper.selectById(stepId);
        return step != null && step.getRetryCount() != null ? step.getRetryCount() : 0;
    }
}
