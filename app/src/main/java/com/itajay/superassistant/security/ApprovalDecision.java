package com.itajay.superassistant.security;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult;

/**
 * 单条审批决策——前端点击同意/拒绝/编辑按钮后提交的数据结构。
 */
public record ApprovalDecision(
        String toolId,
        FeedbackResult result,
        String description,
        String editedArguments
) {

    public static ApprovalDecision approve(String toolId) {
        return new ApprovalDecision(toolId, FeedbackResult.APPROVED, null, null);
    }

    public static ApprovalDecision reject(String toolId, String reason) {
        return new ApprovalDecision(toolId, FeedbackResult.REJECTED, reason, null);
    }

    public static ApprovalDecision edit(String toolId, String newArguments) {
        return new ApprovalDecision(toolId, FeedbackResult.EDITED, null, newArguments);
    }
}
