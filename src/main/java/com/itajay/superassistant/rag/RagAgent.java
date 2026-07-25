package com.itajay.superassistant.rag;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class RagAgent {
    public final ReactAgent reactAgent;
    //系统提示词
    public final String AGENT_DESCRIPTION="";


    public RagAgent(AgentHook raghook, MysqlSaver saver, ChatModel chatModel) {
        this.reactAgent = ReactAgent.builder()
                .name("rag-agent")
                .model(chatModel)
                .instruction("{input}") //用户的输入 在 OverAllState 的 {input} 键中
                .includeContents(false) //让rag agent专注于用户问题进行回答
                .description(AGENT_DESCRIPTION)
                .hooks(raghook)
                .saver(saver)
                .build();
    }

}
