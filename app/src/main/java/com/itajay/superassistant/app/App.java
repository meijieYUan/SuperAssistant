package com.itajay.superassistant.app;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.itajay.superassistant.rag.RagAgent;
import com.itajay.superassistant.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private final ReactAgent superiorAgent;
    private final RagService ragService;
    private final ResourceLoader resourceLoader;
    public final String SYSTEM_PROMPT="";

    public App(ChatModel chatModel, RagAgent ragAgent, RagService ragService, SkillsAgentHook skillsAgentHook,
               ResourceLoader resourceLoader) {
        this.superiorAgent = ReactAgent
                .builder()
                .model(chatModel)
                .tools(AgentTool.create(ragAgent.reactAgent))
                .hooks(skillsAgentHook)
                .systemPrompt(SYSTEM_PROMPT)
                .build();
        this.ragService = ragService;
        this.resourceLoader = resourceLoader;
    }

    @PostMapping("/chat/{threadId}")
    public Map<String, String> chat(@PathVariable String threadId,
                                    @RequestBody ChatRequest request) {
        log.info("Chat request [thread={}]: {}", threadId, request.message());
        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();
            AssistantMessage response = superiorAgent.call(new UserMessage(request.message()), config);
            return Map.of("response", response.getText(), "threadId", threadId);
        } catch (Exception e) {
            log.error("Chat error", e);
            return Map.of("response", "Error: " + e.getMessage());
        }
    }

    @PostMapping("/knowledge/upload")
    public Map<String, Object> uploadKnowledge(@RequestParam("file") MultipartFile file) {
        log.info("Uploading knowledge file: {}", file.getOriginalFilename());
        try {
            Path tempFile = Files.createTempFile("rag-", "-" + file.getOriginalFilename());
            file.transferTo(tempFile.toFile());
            Resource resource = resourceLoader.getResource("file:" + tempFile.toAbsolutePath());

            int chunks = ragService.addSource(resource);

            Files.deleteIfExists(tempFile);
            return Map.of("success", true, "chunks", chunks, "filename", file.getOriginalFilename());
        } catch (IOException e) {
            log.error("Upload failed", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    public record ChatRequest(String message) {}
}