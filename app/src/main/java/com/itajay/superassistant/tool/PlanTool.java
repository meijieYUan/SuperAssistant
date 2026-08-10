package com.itajay.superassistant.tool;

import com.itajay.superassistant.plan.PlanModeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class PlanTool {

    private static final Logger log = LoggerFactory.getLogger(PlanTool.class);

    @Tool(name = "enterPlanMode", description = """
            Enter plan mode for complex task analysis. Only works when the user has enabled plan mode.
            In plan mode: read code/files, search, ask questions, write plans/. Forbidden: modify business code, run non-read-only commands.""")
    public String enterPlanMode(ToolContext toolContext) {
        String threadId = resolveThreadId(toolContext);
        if (!PlanModeContext.isEnabled(threadId)) {
            return "Plan mode is not enabled. Tell the user to toggle Plan Mode on first.";
        }
        boolean wasActive = PlanModeContext.isActive(threadId);
        PlanModeContext.enter(threadId);
        log.info("Entered plan mode [thread={}, wasActive={}]", threadId, wasActive);
        if (wasActive) {
            return "Already in plan mode (thread=" + threadId + "). Continue planning.";
        }
        return "Entered plan mode (thread=" + threadId + "). Allowed: read code/files, search, ask questions, write plans/. "
                + "Forbidden: modify business code, run non-read-only commands. "
                + "Analyze the task, write the plan to plans/" + threadId + ".md, then request user approval.";
    }

    @Tool(name = "exitPlanMode", description = """
            Exit plan mode. approved=true: plan accepted, exit and call todoWrite to decompose into tasks.
            approved=false: plan rejected, stay in plan mode and revise.""")
    public String exitPlanMode(
            @ToolParam(description = "Whether the user approved the plan") boolean approved,
            ToolContext toolContext) {
        String threadId = resolveThreadId(toolContext);
        if (approved) {
            PlanModeContext.exit(threadId);
            log.info("Exited plan mode [thread={}]", threadId);
            return "Exited plan mode. Now call todoWrite(objective) to decompose the plan into persistent todo tasks.";
        }
        return "Plan not approved. Staying in plan mode. Revise the plan based on user feedback, then request approval again.";
    }

    private String resolveThreadId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object value = toolContext.getContext().get("threadId");
            if (value != null) {
                return String.valueOf(value);
            }
        }
        throw new IllegalArgumentException("Missing threadId context, cannot operate plan mode");
    }
}