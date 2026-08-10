package com.itajay.superassistant.tool;

import com.itajay.superassistant.plan.PlanModeContext;
import com.itajay.superassistant.service.FileOperationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class FileOperationTool {

    private static final Logger log = LoggerFactory.getLogger(FileOperationTool.class);
    private final FileOperationService fileService;

    public FileOperationTool(FileOperationService fileService) {
        this.fileService = fileService;
    }

    @Tool(description = "Read the content of a file. Returns the file text content.")
    public String readFile(
            @ToolParam(description = "Path to the file (relative to workspace)") String filePath) {
        log.info("Reading file: {}", filePath);
        try {
            return fileService.readFile(filePath);
        } catch (Exception e) {
            log.error("Read file failed", e);
            return "Failed to read file: " + e.getMessage();
        }
    }

    @Tool(description = "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Parent directories are created automatically. In plan mode only files under plans/ can be written.")
    public String writeFile(
            @ToolParam(description = "Path to the file (relative to workspace)") String filePath,
            @ToolParam(description = "Content to write to the file") String content,
            ToolContext toolContext) {
        if (planModeBlocksPath(toolContext, filePath)) {
            return "计划模式下只允许写入 plans/ 目录下的计划文件";
        }
        log.info("Writing file: {}", filePath);
        try {
            return fileService.writeFile(filePath, content);
        } catch (Exception e) {
            log.error("Write file failed", e);
            return "Failed to write file: " + e.getMessage();
        }
    }

    @Tool(description = "Create a new empty file. Fails if the file already exists. In plan mode only files under plans/ can be created.")
    public String createFile(
            @ToolParam(description = "Path to the new file (relative to workspace)") String filePath,
            ToolContext toolContext) {
        if (planModeBlocksPath(toolContext, filePath)) {
            return "计划模式下只允许在 plans/ 目录下创建计划文件";
        }
        log.info("Creating file: {}", filePath);
        try {
            return fileService.createFile(filePath);
        } catch (Exception e) {
            log.error("Create file failed", e);
            return "Failed to create file: " + e.getMessage();
        }
    }

    @Tool(description = "Delete a file or empty directory. In plan mode only files under plans/ can be deleted.")
    public String deleteFile(
            @ToolParam(description = "Path to the file or empty directory to delete (relative to workspace)") String filePath,
            ToolContext toolContext) {
        if (planModeBlocksPath(toolContext, filePath)) {
            return "计划模式下只允许删除 plans/ 目录下的计划文件";
        }
        log.info("Deleting file: {}", filePath);
        try {
            return fileService.deleteFile(filePath);
        } catch (Exception e) {
            log.error("Delete file failed", e);
            return "Failed to delete file: " + e.getMessage();
        }
    }

    @Tool(description = "List all files and directories in the specified directory.")
    public String listFiles(
            @ToolParam(description = "Path to the directory (relative to workspace, use '.' for current)") String dirPath) {
        log.info("Listing files: {}", dirPath);
        try {
            return fileService.listFiles(dirPath);
        } catch (Exception e) {
            log.error("List files failed", e);
            return "Failed to list files: " + e.getMessage();
        }
    }

    private boolean planModeBlocksPath(ToolContext toolContext, String filePath) {
        return PlanModeContext.isActive(threadId(toolContext)) && !fileService.isInsidePlanDirectory(filePath);
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
