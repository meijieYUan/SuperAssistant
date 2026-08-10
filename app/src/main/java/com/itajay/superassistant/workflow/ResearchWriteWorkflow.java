package com.itajay.superassistant.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.itajay.superassistant.agent.ResearchAgent;
import com.itajay.superassistant.agent.WriterAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Research-Write document workflow.
 * Chains ResearchAgent (gather materials) then WriterAgent (compose structured document).
 * Exposed as both a SequentialAgent and a Tool for the main agent.
 */
@Component
public class ResearchWriteWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ResearchWriteWorkflow.class);

    /** The sequential pipeline: research-agent -> writer-agent. */
    public final SequentialAgent sequentialAgent;

    public ResearchWriteWorkflow(ResearchAgent researchAgent, WriterAgent writerAgent) {
        this.sequentialAgent = SequentialAgent.builder()
                .name("research-write-workflow")
                .description("Research document workflow: research-agent gathers materials, then writer-agent composes a structured document")
                .subAgents(List.of(researchAgent.reactAgent, writerAgent.reactAgent))
                .build();
    }

    @Tool(description = """
            Execute the research-document workflow: first the research-agent searches and collects high-quality materials,
            then the writer-agent composes a structured Markdown document based on the research results.
            Suitable for literature reviews, technical reports, market analysis, and similar tasks.""")
    public String researchWrite(
            @ToolParam(description = "Research topic or document requirement description. Be as detailed as possible.") String topic) {

        log.info("ResearchWrite workflow started [topic={}]", topic);

        try {
            Optional<OverAllState> result = sequentialAgent.invoke(topic);
            String output = result
                    .flatMap(s -> s.value("output"))
                    .map(Object::toString)
                    .orElse("Workflow completed (no output)");

            log.info("ResearchWrite workflow completed");
            return "Research-write workflow result:\n\n" + output;
        } catch (Exception e) {
            log.error("ResearchWrite workflow failed", e);
            return "Research-write workflow failed: " + e.getMessage();
        }
    }
}
