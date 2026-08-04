package com.itajay.superassistant.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class MemoryTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryTool.class);
    private static final int MAX_FACTS = 50;
    private static final Path MEMORY_ROOT = Path.of(".memory");
    private static final Path FACTS_DIR = MEMORY_ROOT.resolve("facts");
    private static final Path INDEX_FILE = MEMORY_ROOT.resolve("MEMORY.md");
    private static final Path LOG_FILE = MEMORY_ROOT.resolve("consolidate-log.md");

    public MemoryTool() {
        try {
            Files.createDirectories(FACTS_DIR);
            if (!Files.exists(INDEX_FILE)) rebuildIndex(List.of());
        } catch (IOException e) {
            log.error("Failed to initialize memory directory", e);
        }
    }

    // ================================================================
    //  remember  鈥?鍒涘缓 / 鏇存柊涓€鏉′簨瀹?    // ================================================================

    @Tool(description = "Remember an important fact or preference about the user. Creates or updates a fact file and refreshes the memory index. Types: preference, project, event, knowledge, contact. Importance 1-10 (higher = more important).")
    public String remember(
            @ToolParam(description = "Fact type: preference, project, event, knowledge, or contact") String type,
            @ToolParam(description = "Short topic/title for this fact, e.g. '缂栫▼璇█鍋忓ソ'") String topic,
            @ToolParam(description = "Detailed content of the fact. Include context and evidence.") String content,
            @ToolParam(description = "Importance score 1-10, 10 being most critical") int importance) {
        try {
            List<Fact> facts = loadAllFacts();
            int importanceClamped = Math.max(1, Math.min(10, importance));
            String id = type.substring(0, 1).toLowerCase() + String.format("%02d", facts.size() + 1);

            // Check if similar topic already exists
            Fact existing = findSimilar(facts, topic);
            if (existing != null) {
                existing.type = type;
                existing.topic = topic;
                existing.content = content;
                existing.importance = importanceClamped;
                existing.updated = LocalDateTime.now();
                writeFactFile(existing);
                rebuildIndex(facts);
                log.info("Updated fact [{}]: {}", existing.id, topic);
                return String.format("Updated existing fact [%s] '%s' (importance: %d)",
                        existing.id, topic, importanceClamped);
            }

            Fact fact = new Fact(id, type, topic, content, importanceClamped,
                    LocalDateTime.now(), LocalDateTime.now());
            writeFactFile(fact);
            facts.add(fact);

            // Enforce budget
            if (facts.size() > MAX_FACTS) {
                facts.sort(Comparator.comparingInt(f -> f.importance));
                List<Fact> removed = new ArrayList<>();
                while (facts.size() > MAX_FACTS) {
                    removed.add(facts.remove(0));
                }
                logRemovals(removed, "budget overflow");
            }

            rebuildIndex(facts);
            log.info("Remembered fact [{}]: {} (importance: {})", id, topic, importanceClamped);
            return String.format("Fact remembered [%s] '%s' (type: %s, importance: %d/%d, total: %d/%d)",
                    id, topic, type, importanceClamped, 10, facts.size(), MAX_FACTS);

        } catch (IOException e) {
            log.error("Failed to remember fact", e);
            return "Error saving memory: " + e.getMessage();
        }
    }

    // ================================================================
    //  recall  鈥?妫€绱簨瀹?    // ================================================================

    @Tool(description = "Recall facts from memory. Searches by topic/type keyword match. Returns matching fact summaries with their IDs for reference.")
    public String recall(
            @ToolParam(description = "Search query to match against fact topics and content") String query) {
        try {
            List<Fact> facts = loadAllFacts();
            if (facts.isEmpty()) return "No memories found. The memory is empty.";

            String lower = query.toLowerCase();
            List<Fact> matches = facts.stream()
                    .filter(f -> f.topic.toLowerCase().contains(lower)
                            || f.type.toLowerCase().contains(lower)
                            || f.content.toLowerCase().contains(lower))
                    .sorted((a, b) -> Integer.compare(b.importance, a.importance))
                    .toList();

            if (matches.isEmpty()) {
                return String.format("No memories match '%s'. Use listMemories to see all facts.", query);
            }

            StringBuilder sb = new StringBuilder("Memory matches for '" + query + "':\n\n");
            for (Fact f : matches) {
                sb.append(formatFactSummary(f)).append("\n");
            }
            return sb.toString().trim();

        } catch (IOException e) {
            return "Error recalling memories: " + e.getMessage();
        }
    }

    // ================================================================
    //  listMemories  鈥?鍒楀嚭鎵€鏈変簨瀹炴憳瑕?    // ================================================================

    @Tool(description = "List all remembered facts with their IDs, types, importance, and summaries. Use this to get an overview before deciding what to update or delete.")
    public String listMemories() {
        try {
            List<Fact> facts = loadAllFacts();
            if (facts.isEmpty()) return "Memory is empty. No facts stored yet.";

            Map<String, List<Fact>> grouped = facts.stream()
                    .collect(Collectors.groupingBy(f -> f.type,
                            () -> new LinkedHashMap<>(),
                            Collectors.toList()));

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Memory: %d/%d facts\n\n", facts.size(), MAX_FACTS));

            for (var entry : grouped.entrySet()) {
                sb.append("## ").append(capitalize(entry.getKey())).append(" (").append(entry.getValue().size()).append(")\n");
                for (Fact f : entry.getValue().stream()
                        .sorted((a, b) -> Integer.compare(b.importance, a.importance))
                        .toList()) {
                    sb.append(formatFactSummary(f)).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString().trim();

        } catch (IOException e) {
            return "Error listing memories: " + e.getMessage();
        }
    }

    // ================================================================
    //  deleteFact  鈥?鍒犻櫎涓€鏉′簨瀹?    // ================================================================

    @Tool(description = "Delete a specific fact by its ID. Use this to remove outdated or incorrect memories.")
    public String deleteFact(
            @ToolParam(description = "Fact ID to delete, e.g. p01, j03") String id) {
        try {
            List<Fact> facts = loadAllFacts();
            Fact target = facts.stream().filter(f -> f.id.equals(id)).findFirst().orElse(null);
            if (target == null) return "Fact [" + id + "] not found.";

            Path file = FACTS_DIR.resolve(id + ".md");
            Files.deleteIfExists(file);
            facts.remove(target);
            rebuildIndex(facts);
            logRemovals(List.of(target), "manual deletion");
            return String.format("Deleted fact [%s] '%s'", id, target.topic);

        } catch (IOException e) {
            return "Error deleting fact: " + e.getMessage();
        }
    }

    // ================================================================
    //  consolidateMemories  鈥?璁板繂鍚堝苟鍘嬬缉
    // ================================================================

    @Tool(description = "Consolidate memories by removing facts with importance < 4 and trimming to the 50-fact budget. Returns a summary of what was removed. Call this when memory is full or has low-quality entries.")
    public String consolidateMemories() {
        try {
            List<Fact> facts = loadAllFacts();
            int before = facts.size();
            List<Fact> removed = new ArrayList<>();

            // Remove low importance (1-3)
            List<Fact> lowImportance = facts.stream()
                    .filter(f -> f.importance < 4).toList();
            for (Fact f : lowImportance) {
                Files.deleteIfExists(FACTS_DIR.resolve(f.id + ".md"));
                removed.add(f);
            }
            facts.removeAll(lowImportance);

            // If still over budget, remove lowest importance
            if (facts.size() > MAX_FACTS) {
                facts.sort(Comparator.comparingInt(f -> f.importance));
                while (facts.size() > MAX_FACTS) {
                    Fact f = facts.remove(0);
                    Files.deleteIfExists(FACTS_DIR.resolve(f.id + ".md"));
                    removed.add(f);
                }
            }

            // Renumber remaining facts
            Map<String, List<Fact>> byType = facts.stream()
                    .collect(Collectors.groupingBy(f -> f.type, LinkedHashMap::new, Collectors.toList()));
            List<Fact> renumbered = new ArrayList<>();
            for (var entry : byType.entrySet()) {
                int seq = 1;
                for (Fact f : entry.getValue().stream()
                        .sorted((a, b) -> Integer.compare(b.importance, a.importance))
                        .toList()) {
                    String newId = entry.getKey().substring(0, 1).toLowerCase() + String.format("%02d", seq++);
                    if (!f.id.equals(newId)) {
                        Files.deleteIfExists(FACTS_DIR.resolve(f.id + ".md"));
                        f.id = newId;
                        writeFactFile(f);
                    }
                    renumbered.add(f);
                }
            }

            rebuildIndex(renumbered);
            logRemovals(removed, "consolidation");

            int after = renumbered.size();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Memory consolidated: %d -> %d facts (%d removed)\n\n", before, after, before - after));
            if (!removed.isEmpty()) {
                sb.append("Removed:\n");
                for (Fact f : removed) {
                    sb.append(String.format("  - [%s] %s (importance: %d)\n", f.id, f.topic, f.importance));
                }
            }
            sb.append(String.format("\nCurrent: %d/%d facts. Budget healthy.", after, MAX_FACTS));
            return sb.toString();

        } catch (IOException e) {
            return "Error consolidating memories: " + e.getMessage();
        }
    }

    // ================================================================
    //  鍐呴儴鏂规硶
    // ================================================================

    private static class Fact {
        String id, type, topic, content;
        int importance;
        LocalDateTime created, updated;

        Fact(String id, String type, String topic, String content,
             int importance, LocalDateTime created, LocalDateTime updated) {
            this.id = id;
            this.type = type;
            this.topic = topic;
            this.content = content;
            this.importance = importance;
            this.created = created;
            this.updated = updated;
        }
    }

    private Fact findSimilar(List<Fact> facts, String topic) {
        String lower = topic.toLowerCase().trim();
        return facts.stream()
                .filter(f -> f.topic.toLowerCase().trim().equals(lower))
                .findFirst().orElse(null);
    }

    private List<Fact> loadAllFacts() throws IOException {
        if (!Files.exists(FACTS_DIR)) return new ArrayList<>();
        List<Fact> facts = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(FACTS_DIR, "*.md")) {
            for (Path file : stream) {
                try {
                    String text = Files.readString(file);
                    Fact f = parseFactFile(text);
                    if (f != null) facts.add(f);
                } catch (Exception e) {
                    log.warn("Failed to parse fact file: {}", file, e);
                }
            }
        }
        facts.sort((a, b) -> Integer.compare(b.importance, a.importance));
        return facts;
    }

    private Fact parseFactFile(String text) {
        try {
            String id = extractYaml(text, "id");
            String type = extractYaml(text, "type");
            String topic = extractYaml(text, "topic");
            String importanceStr = extractYaml(text, "importance");
            String createdStr = extractYaml(text, "created");
            String updatedStr = extractYaml(text, "updated");

            if (id == null || type == null || topic == null) return null;

            // Extract content after YAML frontmatter
            int contentStart = text.indexOf("---\n");
            if (contentStart >= 0) {
                contentStart = text.indexOf("---\n", contentStart + 4);
                if (contentStart >= 0) contentStart += 4;
            }
            String content = contentStart > 0 ? text.substring(contentStart).trim() : text;

            int importance = parseOrDefault(importanceStr, 5);
            LocalDateTime created = parseDateTime(createdStr);
            LocalDateTime updated = parseDateTime(updatedStr);

            return new Fact(id, type, topic, content, importance, created, updated);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractYaml(String text, String key) {
        Pattern p = Pattern.compile("^" + key + ":\\s*(.+)$", Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private int parseOrDefault(String s, int def) {
        try { return s != null ? Integer.parseInt(s) : def; } catch (NumberFormatException e) { return def; }
    }

    private LocalDateTime parseDateTime(String s) {
        try {
            return s != null ? LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME) : LocalDateTime.now();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private void writeFactFile(Fact f) throws IOException {
        String content = String.format("""
                ---
                id: %s
                type: %s
                topic: %s
                importance: %d
                created: %s
                updated: %s
                ---

                # %s

                %s
                """,
                f.id, f.type, f.topic, f.importance,
                f.created.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                f.updated.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                f.topic, f.content);
        Files.writeString(FACTS_DIR.resolve(f.id + ".md"), content);
    }

    private void rebuildIndex(List<Fact> facts) throws IOException {
        Map<String, List<Fact>> grouped = facts.stream()
                .collect(Collectors.groupingBy(f -> f.type,
                        () -> new LinkedHashMap<>(),
                        Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        sb.append("# Memory Index\n");
        sb.append(String.format("> Last updated: %s\n", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        sb.append(String.format("> Total facts: %d / %d\n\n", facts.size(), MAX_FACTS));

        if (facts.isEmpty()) {
            sb.append("*No facts stored yet. The agent will add facts as it learns about the user.*\n");
        } else {
            for (var entry : grouped.entrySet()) {
                String label = capitalize(entry.getKey());
                sb.append(String.format("## %s (%d)\n", label, entry.getValue().size()));
                for (Fact f : entry.getValue().stream()
                        .sorted((a, b) -> Integer.compare(b.importance, a.importance))
                        .toList()) {
                    String date = f.created.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    sb.append(String.format("- [%s] %s | %s%d | %s\n",
                            f.id, f.topic, getStarRating(f.importance), f.importance, date));
                }
                sb.append("\n");
            }
        }

        sb.append("---\n");
        sb.append("*This index is auto-generated. Facts are stored in `.memory/facts/`. ");
        sb.append("Use `listMemories` for full details.*\n");

        Files.writeString(INDEX_FILE, sb.toString());
    }

    private String formatFactSummary(Fact f) {
        String snippet = f.content.length() > 80 ? f.content.substring(0, 80) + "..." : f.content;
        return String.format("[%s] %s | %s | %s%d | %s 鈥?%s",
                f.id, capitalize(f.type), f.topic, getStarRating(f.importance), f.importance,
                f.created.format(DateTimeFormatter.ofPattern("MM-dd")), snippet);
    }

    private void logRemovals(List<Fact> removed, String reason) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("## %s 鈥?%s (%d facts removed)",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), reason, removed.size()));
        for (Fact f : removed) {
            lines.add(String.format("- [%s] %s (importance: %d)", f.id, f.topic, f.importance));
        }
        lines.add("");
        if (Files.exists(LOG_FILE)) {
            lines.addAll(Files.readAllLines(LOG_FILE));
        }
        Files.write(LOG_FILE, lines);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private String getStarRating(int importance) {
        int stars = Math.max(1, Math.min(5, importance / 2));
        return "\u2605".repeat(stars);
    }
}
