package com.itajay.superassistant.tool;

import com.itajay.superassistant.plan.PlanModeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class TerminalTool {

    private static final Logger log = LoggerFactory.getLogger(TerminalTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 8000;
    private static final Set<String> MUTATING_TOKENS = Set.of(
            "rm", "del", "erase", "rd", "rmdir", "mv", "move", "ren", "cp", "copy", "xcopy",
            "mkdir", "md", "touch", "install", "format", "taskkill", "kill", "pkill",
            "shutdown", "restart", "setx", "push", "commit", "checkout", "reset", "clean",
            "stash", "apply", "add");
    private final String workspaceDir;

    public TerminalTool() {
        this.workspaceDir = System.getProperty("user.dir");
    }

    @Tool(description = "Execute a shell/terminal command and return the output. Supports common commands like dir/ls, echo, mkdir, type/cat, etc. Use with caution. Output is truncated at 8000 characters and commands time out after 30 seconds.")
    public String executeCommand(
            @ToolParam(description = "The shell command to execute, e.g. 'dir' on Windows or 'ls -la' on Linux/Mac") String command,
            @ToolParam(description = "Working directory for the command. Use '.' for current workspace. Defaults to workspace root.") String workingDir,
            ToolContext toolContext) {
        if (PlanModeContext.isActive(threadId(toolContext)) && !isReadOnlyCommand(command)) {
            return "计划模式下禁止运行非只读命令: " + command;
        }
        log.info("Executing command: {} in dir: {}", command, workingDir);

        File dir;
        if (workingDir == null || workingDir.isBlank() || workingDir.equals(".")) {
            dir = new File(workspaceDir);
        } else if (new File(workingDir).isAbsolute()) {
            dir = new File(workingDir);
        } else {
            dir = new File(workspaceDir, workingDir);
        }

        if (!dir.exists() || !dir.isDirectory()) {
            return "Error: directory not found: " + dir.getAbsolutePath();
        }

        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.directory(dir);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < MAX_OUTPUT_CHARS) {
                        output.append(line).append("\n");
                    }
                }
            }

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                output.append("\n[Command timed out after ").append(DEFAULT_TIMEOUT_SECONDS).append("s]");
            }

            int exitCode = finished ? process.exitValue() : -1;
            String header = String.format("Command: %s\nDirectory: %s\nExit code: %d\n\n",
                    command, dir.getAbsolutePath(), exitCode);

            if (output.length() == 0) {
                return header + "(no output)";
            }
            if (output.length() >= MAX_OUTPUT_CHARS) {
                output.append("\n[Output truncated at ").append(MAX_OUTPUT_CHARS).append(" chars]");
            }

            return header + output.toString().trim();

        } catch (Exception e) {
            log.error("Command execution failed: {}", command, e);
            return "Error executing command: " + e.getMessage();
        }
    }

    private boolean isReadOnlyCommand(String command) {
        String cmd = command == null ? "" : command.trim().toLowerCase();
        if (cmd.isBlank() || cmd.contains(">") || cmd.contains("&&") || cmd.contains(";")) {
            return false;
        }
        for (String token : cmd.split("\\s+")) {
            if (MUTATING_TOKENS.contains(token)) {
                return false;
            }
        }
        String first = cmd.split("\\s+")[0];
        return switch (first) {
            case "dir", "ls", "pwd", "type", "cat", "more", "less", "find", "rg", "grep",
                 "where", "which", "head", "tail", "wc", "sort", "uniq", "tree" -> true;
            case "git" -> isReadOnlyGit(cmd);
            case "java" -> cmd.matches("java\\s+(-version|--version|--help).*");
            case "node" -> cmd.matches("node\\s+(-v|--version|--help).*");
            case "npm" -> cmd.matches("npm\\s+(-v|--version|--help).*");
            case "mvn" -> cmd.matches("mvn\\s+(-v|--version|--help).*");
            case "docker" -> cmd.matches("docker\\s+(ps|images|version|help|inspect).*");
            default -> false;
        };
    }

    private boolean isReadOnlyGit(String cmd) {
        String[] parts = cmd.split("\\s+");
        if (parts.length < 2) {
            return false;
        }
        return switch (parts[1]) {
            case "status", "diff", "log", "show", "branch", "remote", "config", "help",
                 "ls-files", "ls-tree", "grep" -> true;
            default -> false;
        };
    }

    private String threadId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object value = toolContext.getContext().get("threadId");
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
