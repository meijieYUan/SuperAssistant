package com.itajay.superassistant.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private final HttpClient httpClient;

    public WebSearchService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Search the web using DuckDuckGo HTML (no API key required).
     * Returns the top search result snippets.
     */
    public String search(String query) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://html.duckduckgo.com/html/?q=" + encodedQuery;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SuperAssistant/1.0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return parseDuckDuckGoResults(response.body());
        }
        return "Search returned status: " + response.statusCode();
    }

    /**
     * Crawl a web page and extract its text content.
     */
    public String crawl(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) SuperAssistant/1.0")
                .timeout(15000)
                .get();

        // Remove script and style elements
        doc.select("script, style, nav, footer, header, aside").remove();

        String title = doc.title();
        String bodyText = doc.body().text();

        // Truncate if too long (max ~8000 chars to avoid token limits)
        if (bodyText.length() > 8000) {
            bodyText = bodyText.substring(0, 8000) + "...";
        }

        return String.format("Title: %s\n\nContent: %s", title, bodyText);
    }

    private String parseDuckDuckGoResults(String html) {
        Document doc = Jsoup.parse(html);
        var results = doc.select(".result");

        if (results.isEmpty()) {
            return "No search results found.";
        }

        StringBuilder sb = new StringBuilder("Search results:\n\n");
        int count = 0;
        for (var result : results) {
            if (count >= 8) break;
            var title = result.select(".result__title");
            var snippet = result.select(".result__snippet");
            var link = result.select(".result__url");

            if (!title.isEmpty()) {
                sb.append(count + 1).append(". **").append(title.text().trim()).append("**\n");
            }
            if (!snippet.isEmpty()) {
                sb.append("   ").append(snippet.text().trim()).append("\n");
            }
            if (!link.isEmpty()) {
                sb.append("   URL: ").append(link.text().trim()).append("\n");
            }
            sb.append("\n");
            count++;
        }
        return sb.toString().trim();
    }
}
