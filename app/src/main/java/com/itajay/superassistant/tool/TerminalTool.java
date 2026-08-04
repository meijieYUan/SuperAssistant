package com.itajay.superassistant.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

@Component
public class TerminalTool {

    private static final Logger log = LoggerFactory.getLogger(TerminalTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 8000;
    private final String workspaceDir;

    public TerminalTool() {
        this.workspaceDir = System.getProperty("user.dir");
    }

    @Tool(description = "Execute a shell/terminal command and return the output. Supports common commands like dir/ls, echo, mkdir, type/cat, etc. Use with caution. Output is truncated at 8000 characters and commands time out after 30 seconds.")
    public String executeCommand(
            @ToolParam(description = "The shell command to execute, e.g. 'dir' on Windows or 'ls -la' on Linux/Mac") String command,
            @ToolParam(description = "Working directory for the command. Use '.' for current workspace. Defaults to workspace root.") String workingDir) {
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
}