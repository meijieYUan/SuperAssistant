package com.itajay.superassistant.tool;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Dynamic sub-agent creation tool.
 * Allows the main agent to spawn a new ReactAgent on-the-fly to handle a specific subtask.
 */
@Component
public class CreateAgentTool {

    private static final Logger log = LoggerFactory.getLogger(CreateAgentTool.class);
    private final ChatModel chatModel;

    public CreateAgentTool(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Tool(description = """
            创建一个子Agent来独立完成指定的子任务。子Agent拥有独立的上下文，会自主执行并返回结果。
            适用于需要将复杂任务拆分为多个独立子任务、或需要特定专业能力来完成子任务的场景。""")
    public String createAgent(
            @ToolParam(description = "子Agent的名称，如 'code-reviewer'、'data-analyzer'") String agentName,
            @ToolParam(description = "子Agent的描述，说明它的能力和用途") String description,
            @ToolParam(description = "子Agent的系统指令，详细说明它应该如何完成子任务，包括步骤、输出格式和约束条件") String instruction,
            @ToolParam(description = "要交给子Agent的具体任务内容") String task) {

        log.info("CreateAgent [name={}]", agentName);

        ReactAgent subAgent = ReactAgent.builder()
                .name(agentName)
                .description(description)
                .model(chatModel)
                .instruction(instruction)
                .build();

        try {
            Optional<OverAllState> result = subAgent.invoke(task);
            String output = result
                    .flatMap(s -> s.value("output"))
                    .map(Object::toString)
                    .orElse("(no output)");

            log.info("CreateAgent [{}] completed", agentName);
            return "Sub-agent [" + agentName + "] completed:\n\n" + output;
        } catch (Exception e) {
            log.error("CreateAgent [{}] failed", agentName, e);
            return "Sub-agent [" + agentName + "] failed: " + e.getMessage();
        }
    }
}
