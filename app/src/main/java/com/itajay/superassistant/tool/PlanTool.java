package com.itajay.superassistant.tool;

import com.itajay.superassistant.entity.PlanTask;
import com.itajay.superassistant.plan.PlanContextHolder;
import com.itajay.superassistant.service.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class PlanTool {

    private static final Logger log = LoggerFactory.getLogger(PlanTool.class);

    private final PlanService planService;

    public PlanTool(PlanService planService) {
        this.planService = planService;
    }

    @Tool(name = "createPlan", description = """
            为复杂多步骤任务生成执行计划。只生成并保存计划，不执行任何子任务。
            当用户请求需要多个专业 Agent 协作（如调研、写作、审查）时调用本工具。
            """)
    public String createPlan(
            @ToolParam(description = "用户希望完成的复杂任务目标") String objective,
            ToolContext toolContext) {
        String threadId = resolveThreadId(toolContext);
        log.info("Creating plan [thread={}]", threadId);

        PlanTask task = planService.createPlan(threadId, objective);
        return "PLAN_PENDING:" + task.getId() + "\n" + task.getPlanJson();
    }

    private String resolveThreadId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object value = toolContext.getContext().get("threadId");
            if (value != null) {
                return String.valueOf(value);
            }
        }
        String fallback = PlanContextHolder.getThreadId();
        if (fallback == null) {
            throw new IllegalArgumentException("缺少 threadId 上下文，无法创建计划");
        }
        return fallback;
    }
}
