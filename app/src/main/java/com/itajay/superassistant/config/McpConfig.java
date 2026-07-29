package com.itajay.superassistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * MCP client configuration — handled entirely by spring-ai-starter-mcp-client-webflux.
 *
 * The starter auto-creates McpSyncClient beans based on spring.ai.mcp.client.* properties
 * and auto-exposes MCP tools as ToolCallbackProvider via McpToolCallbackAutoConfiguration.
 *
 * properties via application.yml.
 */
@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    // All MCP client auto-configuration is handled by:
    //   McpClientAutoConfiguration → creates McpSyncClient
    //   McpToolCallbackAutoConfiguration → creates SyncMcpToolCallbackProvider
}