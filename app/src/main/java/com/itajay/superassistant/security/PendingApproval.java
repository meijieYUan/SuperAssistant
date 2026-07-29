package com.itajay.superassistant.security;

/**
 * 前端渲染审批列表时需要展示的待审批工具调用信息。
 */
public record PendingApproval(
        String toolId,
        String toolName,
        String arguments,
        String description
) {
}
