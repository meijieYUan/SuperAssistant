package com.itajay.superassistant.tool;

import com.itajay.superassistant.service.WebSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private final WebSearchService webSearchService;

    public WebSearchTool(WebSearchService webSearchService) {
        this.webSearchService = webSearchService;
    }

    @Tool(description = "Search the web for information using DuckDuckGo. Returns top search result snippets. Use this when you need up-to-date information or facts not in your training data.")
    public String webSearch(
            @ToolParam(description = "Search query string") String query) {
        log.info("Web search: {}", query);
        try {
            return webSearchService.search(query);
        } catch (Exception e) {
            log.error("Web search failed", e);
            return "Web search failed: " + e.getMessage();
        }
    }

    @Tool(description = "Crawl a web page URL and extract its text content. Use this to read the full content of a specific web page.")
    public String webCrawl(
            @ToolParam(description = "Full URL of the web page to crawl") String url) {
        log.info("Web crawl: {}", url);
        try {
            return webSearchService.crawl(url);
        } catch (Exception e) {
            log.error("Web crawl failed", e);
            return "Web crawl failed: " + e.getMessage();
        }
    }
}
