package com.itajay.superassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class FileOperationService {

    private static final Logger log = LoggerFactory.getLogger(FileOperationService.class);
    private static final Path BASE_PATH = Path.of(System.getProperty("user.dir"));

    /**
     * Read file content with safety checks.
     */
    public String readFile(String filePath) throws IOException {
        Path resolved = resolveSafe(filePath);
        if (!Files.exists(resolved)) {
            return "File not found: " + resolved;
        }
        if (!Files.isRegularFile(resolved)) {
            return "Not a regular file: " + resolved;
        }
        if (Files.size(resolved) > 10 * 1024 * 1024) {
            return "File too large (max 10MB): " + resolved;
        }
        return Files.readString(resolved);
    }

    /**
     * Write content to a file. Creates parent directories if needed.
     */
    public String writeFile(String filePath, String content) throws IOException {
        Path resolved = resolveSafe(filePath);
        Files.createDirectories(resolved.getParent());
        Files.writeString(resolved, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return "File written successfully: " + resolved + " (" + content.length() + " chars)";
    }

    /**
     * Create a new empty file.
     */
    public String createFile(String filePath) throws IOException {
        Path resolved = resolveSafe(filePath);
        Files.createDirectories(resolved.getParent());
        if (Files.exists(resolved)) {
            return "File already exists: " + resolved;
        }
        Files.createFile(resolved);
        return "File created: " + resolved;
    }

    /**
     * Delete a file or empty directory.
     */
    public String deleteFile(String filePath) throws IOException {
        Path resolved = resolveSafe(filePath);
        if (!Files.exists(resolved)) {
            return "File not found: " + resolved;
        }
        Files.delete(resolved);
        return "Deleted: " + resolved;
    }

    /**
     * List files in a directory.
     */
    public String listFiles(String dirPath) throws IOException {
        Path resolved = resolveSafe(dirPath);
        if (!Files.exists(resolved)) {
            return "Directory not found: " + resolved;
        }
        if (!Files.isDirectory(resolved)) {
            return "Not a directory: " + resolved;
        }
        try (Stream<Path> stream = Files.list(resolved)) {
            return stream
                    .map(p -> (Files.isDirectory(p) ? "[DIR]  " : "[FILE] ") + p.getFileName())
                    .collect(Collectors.joining("\n"));
        }
    }

    public boolean isInsidePlanDirectory(String filePath) {
        Path planRoot = BASE_PATH.toAbsolutePath().resolve("plans").normalize();
        return resolveSafe(filePath).startsWith(planRoot);
    }

    private Path resolveSafe(String filePath) {
        Path target = BASE_PATH.resolve(filePath).normalize().toAbsolutePath();
        // Security: prevent path traversal outside workspace
        if (!target.startsWith(BASE_PATH.toAbsolutePath())) {
            throw new SecurityException("Access denied: path outside workspace - " + filePath);
        }
        return target;
    }
}
