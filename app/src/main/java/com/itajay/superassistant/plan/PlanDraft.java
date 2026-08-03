package com.itajay.superassistant.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanDraft(
        String objective,
        List<Step> steps
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(
            String id,
            String agent,
            String goal,
            String acceptanceCriteria,
            List<String> dependsOn
    ) {}
}
