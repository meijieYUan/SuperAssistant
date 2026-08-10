package com.itajay.superassistant.service;

import java.util.List;

/**
 * Structured output DTO for LLM task decomposition.
 * Used with ChatClient.entity() to parse structured task breakdowns.
 */
public record TaskBreakdown(List<TaskStep> steps) {

    public record TaskStep(
            String stepKey,
            String title,
            String description,
            String acceptanceCriteria,
            String dependsOn
    ) {}
}