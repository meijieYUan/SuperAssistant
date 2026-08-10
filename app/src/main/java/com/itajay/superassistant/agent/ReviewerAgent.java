package com.itajay.superassistant.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class ReviewerAgent {

    public final ReactAgent reactAgent;

    public ReviewerAgent(ChatModel chatModel) {
        this.reactAgent = ReactAgent.builder()
                .name("reviewer-agent")
                .description("任务完成审查 agent：按验收标准检查产出，输出 PASS 或 REVISE 以及修改意见。")
                .model(chatModel)
                .instruction("""
                        你是 ReviewerAgent，负责对计划步骤或最终产出进行完成度审查。
                        输入：{input}
                        请按验收标准检查：
                        1. 目标是否完成。
                        2. 内容是否完整、引用是否有来源。
                        3. 格式是否符合要求。
                        输出 JSON：
                        {"verdict": "PASS" 或 "REVISE", "comments": "审查意见"}
                        """)
                .build();
    }
}
