package com.itajay.superassistant.config;

import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.itajay.superassistant.rag.RagAgent;
import com.itajay.superassistant.tool.FileOperationTool;
import com.itajay.superassistant.tool.TodoTool;
import com.itajay.superassistant.tool.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    private final ChatClient chatClient;
    private final TodoTool todoTool;
    private final WebSearchTool webSearchTool;
    private final FileOperationTool fileOperationTool;
    private final RagAgent ragAgent;
    private final ToolCallbackProvider mcpToolCallbackProvider;

    public AgentConfig(ChatClient chatClient,
                       TodoTool todoTool,
                       WebSearchTool webSearchTool,
                       FileOperationTool fileOperationTool,
                       RagAgent ragAgent,
                       ToolCallbackProvider mcpToolCallbackProvider) {
        this.chatClient = chatClient;
        this.todoTool = todoTool;
        this.webSearchTool = webSearchTool;
        this.fileOperationTool = fileOperationTool;
        this.ragAgent = ragAgent;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
    }

    @Bean
    public ReactAgent superiorAgent() {
        log.info("Building SuperiorAgent with local tools + MCP tools from email server");

        ToolCallback ragAgentTool = AgentTool.create(ragAgent.reactAgent);

        return ReactAgent.builder()
                .name("SuperiorAgent")
                .description("Master agent with local tools and MCP email tools.")
                .chatClient(chatClient)
                .instruction("""
                        You are SuperiorAgent, a versatile assistant.

                        Your abilities:
                        1. Daily conversation
                        2. Todo management (TodoTool)
                        3. Web search (WebSearchTool)
                        4. File operations (FileOperationTool)
                        5. Domain knowledge (RagAgent)
                        6. Email sending (MCP email tools: sendEmail, sendEmailBatch)
                        """)
                .methodTools(todoTool, webSearchTool, fileOperationTool)
                .tools(List.of(ragAgentTool))
                .toolCallbackProviders(mcpToolCallbackProvider)
                .build();
    }
}