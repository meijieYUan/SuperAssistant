package com.itajay.superassistant.rag;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class RagAgent {
    public final ReactAgent reactAgent;
    //系统提示词
    public final String AGENT_DESCRIPTION="""
    这是一个RagAgent，是专业领域的专家，任何专业的知识问题都需要调用该工具回答。
    tips：如果用户提到 `面试`，`复习`，`知识检索`，`专业回答`，`向量库`，`文档库`等相关提示词时，必须调用该工具回答用户。
    """;


    public RagAgent(RagHook raghook,
                    ChatModel chatModel,
                    CustomMessageAgentHook customMessageAgentHook) {
        this.reactAgent = ReactAgent.builder()
                .name("rag-agent")
                .model(chatModel)
                .instruction("{input}") //用户的输入 在 OverAllState 的 {input} 键中
                .includeContents(false) //让rag agent专注于用户问题进行回答
                .description(AGENT_DESCRIPTION)
                .hooks(raghook, customMessageAgentHook)
                .build();
    }

}
