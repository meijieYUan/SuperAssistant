package com.itajay.superassistant.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class WeatherTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);
    private final HttpClient httpClient;

    public WeatherTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Tool(description = "Get current weather for a city. Returns temperature, conditions, wind, humidity. City examples: Beijing, Shanghai, Shenzhen, Chengdu, Tokyo, New York.")
    public String getWeather(
            @ToolParam(description = "City name, e.g. Beijing, Shanghai, Shenzhen") String city) {
        log.info("Getting weather for: {}", city);
        try {
            String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String url = "https://wttr.in/" + encoded + "?format=j1";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "SuperAssistant/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return parseWeather(response.body(), city);
            }
            return "Weather service returned status: " + response.statusCode();
        } catch (Exception e) {
            log.error("Weather query failed for: {}", city, e);
            return "Failed to get weather: " + e.getMessage();
        }
    }

    private String parseWeather(String json, String city) {
        StringBuilder sb = new StringBuilder("Weather for " + city + ":\n\n");
        try {
            String current = extractCurrent(json);
            if (current == null) return "Could not parse weather data.";

            String temp = jsonVal(current, "temp_C");
            String feels = jsonVal(current, "FeelsLikeC");
            String humidity = jsonVal(current, "humidity");
            String wind = jsonVal(current, "windspeedKmph");
            String dir = jsonVal(current, "winddir16Point");
            String desc = jsonVal(jsonArr(current, "weatherDesc"), "value");
            String vis = jsonVal(current, "visibility");

            sb.append("  Temperature: ").append(notNull(temp)).append("\u00b0C");
            if (feels != null) sb.append(" (feels like ").append(feels).append("\u00b0C)");
            sb.append("\n");
            sb.append("  Condition: ").append(notNull(desc)).append("\n");
            sb.append("  Humidity: ").append(notNull(humidity)).append("%\n");
            sb.append("  Wind: ").append(notNull(dir)).append(" ").append(notNull(wind)).append(" km/h\n");
            if (vis != null) sb.append("  Visibility: ").append(vis).append(" km\n");

            String forecast = extractFirstForecast(json);
            if (forecast != null) {
                String maxT = jsonVal(forecast, "maxtempC");
                String minT = jsonVal(forecast, "mintempC");
                if (maxT != null && minT != null) {
                    sb.append("  Today: ").append(minT).append("\u00b0C ~ ").append(maxT).append("\u00b0C");
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing weather: {}", e.getMessage());
        }
        return sb.toString().trim();
    }

    private String extractCurrent(String json) {
        int s = json.indexOf("\"current_condition\"");
        if (s < 0) return null;
        s = json.indexOf("[", s);
        if (s < 0) return null;
        int d = 1, i = s + 1;
        while (i < json.length() && d > 0) {
            if (json.charAt(i) == '[') d++;
            else if (json.charAt(i) == ']') d--;
            i++;
        }
        return d == 0 ? json.substring(s + 1, i - 1) : null;
    }

    private String extractFirstForecast(String json) {
        int s = json.indexOf("\"weather\"");
        if (s < 0) return null;
        s = json.indexOf("[", s);
        if (s < 0) return null;
        s = json.indexOf("{", s);
        if (s < 0) return null;
        int d = 1, i = s + 1;
        while (i < json.length() && d > 0) {
            if (json.charAt(i) == '{') d++;
            else if (json.charAt(i) == '}') d--;
            i++;
        }
        return d == 0 ? json.substring(s + 1, i - 1) : null;
    }

    private String jsonVal(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        i = json.indexOf(":", i);
        if (i < 0) return null;
        i++;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '"')) i++;
        int e = json.indexOf("\"", i);
        if (e < 0) { e = json.indexOf(",", i); if (e < 0) e = json.indexOf("}", i); }
        if (e < 0) return null;
        return json.substring(i, e).trim().replace("\"", "");
    }

    private String jsonArr(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        i = json.indexOf("[", i);
        if (i < 0) return null;
        int e = json.indexOf("]", i);
        return e > 0 ? json.substring(i + 1, e) : null;
    }

    private String notNull(String s) { return s != null ? s : "N/A"; }
}