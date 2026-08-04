package com.itajay.superassistant.app;

import com.itajay.superassistant.entity.PlanTask;
import com.itajay.superassistant.plan.PlanProgressNotifier;
import com.itajay.superassistant.service.AgentRunLogService;
import com.itajay.superassistant.service.PlanExecutionService;
import com.itajay.superassistant.service.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private static final Logger log = LoggerFactory.getLogger(PlanController.class);

    private final PlanService planService;
    private final PlanExecutionService planExecutionService;
    private final PlanProgressNotifier planProgressNotifier;
    private final AgentRunLogService agentRunLogService;

    public PlanController(PlanService planService,
                          PlanExecutionService planExecutionService,
                          PlanProgressNotifier planProgressNotifier,
                          AgentRunLogService agentRunLogService) {
        this.planService = planService;
        this.planExecutionService = planExecutionService;
        this.planProgressNotifier = planProgressNotifier;
        this.agentRunLogService = agentRunLogService;
    }

    @GetMapping("/{planId}")
    public Map<String, Object> getPlan(@PathVariable Long planId) {
        return planService.getPlanResponse(planId);
    }

    @GetMapping("/{planId}/events")
    public SseEmitter streamProgress(@PathVariable Long planId) {
        return planProgressNotifier.subscribe(planId);
    }

    @GetMapping("/{planId}/runs")
    public List<?> getRunLogs(@PathVariable Long planId,
                              @RequestParam(required = false) String threadId,
                              @RequestParam(required = false) String agentName,
                              @RequestParam(defaultValue = "50") int limit) {
        return agentRunLogService.search(planId, threadId, agentName, limit);
    }

    @PostMapping("/{planId}/approve")
    public Map<String, Object> approve(@PathVariable Long planId) {
        log.info("Approve plan [id={}]", planId);
        PlanTask task = planService.getPlan(planId);
        if (!"AWAITING_APPROVAL".equals(task.getStatus())) {
            return Map.of("type", "ERROR", "planId", planId, "message",
                    "计划状态不是 AWAITING_APPROVAL，无法批准: " + task.getStatus());
        }

        task.setStatus("APPROVED");
        task.setApprovedAt(LocalDateTime.now());
        planService.updateTask(task);

        return planExecutionService.start(planId);
    }

    @PostMapping("/{planId}/reject")
    public Map<String, Object> reject(@PathVariable Long planId,
                                      @RequestBody(required = false) Map<String, String> body) {
        log.info("Reject plan [id={}]", planId);
        PlanTask task = planService.getPlan(planId);
        if (!"AWAITING_APPROVAL".equals(task.getStatus())) {
            return Map.of("type", "ERROR", "planId", planId, "message",
                    "计划状态不是 AWAITING_APPROVAL，无法拒绝: " + task.getStatus());
        }

        task.setStatus("REJECTED");
        task.setErrorMessage(body != null ? body.get("reason") : null);
        planService.updateTask(task);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "PLAN_REJECTED");
        response.put("planId", planId);
        response.put("status", task.getStatus());
        return response;
    }

    @PostMapping("/{planId}/revise")
    public Map<String, Object> revise(@PathVariable Long planId,
                                      @RequestBody(required = false) Map<String, String> body) {
        log.info("Revise plan [id={}]", planId);
        String reason = body != null ? body.get("reason") : null;
        if (reason == null || reason.isBlank()) {
            return Map.of("type", "ERROR", "planId", planId, "message", "修订原因不能为空");
        }

        try {
            PlanTask task = planService.revisePlan(planId, reason);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "PLAN_REVISED");
            response.put("planId", task.getId());
            response.putAll(planService.getPlanResponse(planId));
            return response;
        } catch (IllegalArgumentException e) {
            return Map.of("type", "ERROR", "planId", planId, "message", e.getMessage());
        }
    }
}
