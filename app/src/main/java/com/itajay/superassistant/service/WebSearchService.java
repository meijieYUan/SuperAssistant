package com.itajay.superassistant.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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
     * Search the web using Bing (cn.bing.com, accessible in China).
     * Returns the top search result snippets.
     */
    public String search(String query) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://cn.bing.com/search?q=" + encodedQuery + "&setlang=zh-cn";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200 || response.statusCode() == 302) {
            return parseBingResults(response.body());
        }
        return "Search returned status: " + response.statusCode();
    }

    /**
     * Crawl a web page and extract its text content.
     */
    public String crawl(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
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

    private String parseBingResults(String html) {
        Document doc = Jsoup.parse(html);
        Elements results = doc.select("li.b_algo");

        if (results.isEmpty()) {
            // Fallback: try alternative Bing result selectors
            results = doc.select("ol#b_results > li.b_algo");
        }
        if (results.isEmpty()) {
            results = doc.select(".b_algo");
        }

        if (results.isEmpty()) {
            return "No search results found.";
        }

        StringBuilder sb = new StringBuilder("Search results:\n\n");
        int count = 0;
        for (Element result : results) {
            if (count >= 8) break;

            Element titleEl = result.selectFirst("h2 a");
            Element snippetEl = result.selectFirst(".b_caption p, .b_lineclamp2, .b_lineclamp4");
            Element linkEl = result.selectFirst("cite");

            if (titleEl != null) {
                sb.append(count + 1).append(". **").append(titleEl.text().trim()).append("**\n");
            }
            if (snippetEl != null) {
                sb.append("   ").append(snippetEl.text().trim()).append("\n");
            }
            if (linkEl != null) {
                sb.append("   URL: ").append(linkEl.text().trim()).append("\n");
            }
            sb.append("\n");
            count++;
        }
        return sb.toString().trim();
    }
}
