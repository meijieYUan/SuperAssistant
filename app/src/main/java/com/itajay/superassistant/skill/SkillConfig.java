package com.itajay.superassistant.skill;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SkillConfig {
    @Bean
    public SkillsAgentHook skillsAgentHook(){
        SkillRegistry skillRegistry = FileSystemSkillRegistry.builder()
                .userSkillsDirectory("app/src/main/resources/skills")
                .build();
        return SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(true)
                .build();
    }
}
