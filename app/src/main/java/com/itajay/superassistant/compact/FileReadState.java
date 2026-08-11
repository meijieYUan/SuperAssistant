package com.itajay.superassistant.compact;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks which files have been read during the current session, with timestamps.
 *
 * Used by {@link ContextCompactor} to determine which files to re-inject
 * after a full LLM compaction. Files are deduplicated and sorted by last access time.
 */
public class FileReadState {

    private final Map<String, Instant> accessTimes = new LinkedHashMap<>();

    /** Record a file access from a tool response message. */
    public void recordAccess(String filePath) {
        if (filePath != null && !filePath.isBlank()) {
            accessTimes.put(normalize(filePath), Instant.now());
        }
    }

    /**
     * Scan messages for file read/write operations and populate the state.
     * Call this before compaction to build the cache from the message history.
     */
    public void scanMessages(List<Message> messages) {
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                    String name = resp.name();
                    if ("readFile".equals(name) || "writeFile".equals(name)) {
                        String data = resp.responseData();
                        if (data != null) {
                            for (String path : extractPaths(data)) {
                                accessTimes.putIfAbsent(normalize(path), Instant.now());
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Get the most recently accessed files, sorted by timestamp (newest first).
     *
     * @param maxFiles  maximum number of files to return
     * @return list of file paths, newest first
     */
    public List<String> getRecentFiles(int maxFiles) {
        return accessTimes.entrySet().stream()
                .sorted(Map.Entry.<String, Instant>comparingByValue().reversed())
                .limit(maxFiles)
                .map(Map.Entry::getKey)
                .toList();
    }

    public int size() {
        return accessTimes.size();
    }

    // ── helpers ──

    private static String normalize(String path) {
        try {
            return Path.of(path).normalize().toString().replace('\\', '/');
        } catch (Exception e) {
            return path;
        }
    }

    static List<String> extractPaths(String data) {
        List<String> paths = new ArrayList<>();
        for (String line : data.split("[\r\n]+")) {
            line = line.trim();
            if (matchesPath(line) && line.length() < 500) {
                paths.add(line);
            }
        }
        return paths;
    }

    private static boolean matchesPath(String s) {
        if (s.startsWith("/") || s.matches("^[A-Za-z]:\\\\.*")) return true;
        if (s.matches("^\\.?[\\\\/].*")) return true;
        // Also match simple relative paths like "src/main/..."
        if (s.matches("^[a-zA-Z0-9_].*[\\\\/].*") && !s.contains(" ")) return true;
        return false;
    }
}
