package com.itajay.superassistant.tool;

import com.itajay.superassistant.entity.TodoTask;
import com.itajay.superassistant.service.TodoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class TodoTool {

    private static final Logger log = LoggerFactory.getLogger(TodoTool.class);
    private final TodoService todoService;

    public TodoTool(TodoService todoService) {
        this.todoService = todoService;
    }

    @Tool(description = "Query todo tasks by optional filters: status (PENDING/IN_PROGRESS/COMPLETED/CANCELLED), priority (LOW/MEDIUM/HIGH/URGENT), and keyword search in title/description")
    public String queryTodos(
            @ToolParam(description = "Task status filter, one of: PENDING, IN_PROGRESS, COMPLETED, CANCELLED") String status,
            @ToolParam(description = "Task priority filter, one of: LOW, MEDIUM, HIGH, URGENT") String priority,
            @ToolParam(description = "Keyword to search in task title and description") String keyword) {

        log.info("Querying todos: status={}, priority={}, keyword={}", status, priority, keyword);
        List<TodoTask> tasks = todoService.queryTasks(status, priority, keyword);

        if (tasks.isEmpty()) {
            return "No todo tasks found matching the criteria.";
        }
        return formatTaskList(tasks);
    }

    @Tool(description = "Get all pending (not yet completed) todo tasks")
    public String getPendingTodos() {
        List<TodoTask> tasks = todoService.getPendingTasks();
        if (tasks.isEmpty()) {
            return "No pending todo tasks. Great job!";
        }
        return "Pending tasks (" + tasks.size() + "):\n\n" + formatTaskList(tasks);
    }

    @Tool(description = "Get all overdue todo tasks (past due date and not completed)")
    public String getOverdueTodos() {
        List<TodoTask> tasks = todoService.getOverdueTasks();
        if (tasks.isEmpty()) {
            return "No overdue tasks. Everything is on track!";
        }
        return "Overdue tasks (" + tasks.size() + "):\n\n" + formatTaskList(tasks);
    }

    @Tool(description = "Get all todo tasks with a specific priority level")
    public String getTodosByPriority(
            @ToolParam(description = "Priority level: LOW, MEDIUM, HIGH, URGENT") String priority) {
        List<TodoTask> tasks = todoService.getByPriority(priority);
        return tasks.isEmpty() ? "No " + priority + " priority tasks."
                : priority + " priority tasks (" + tasks.size() + "):\n\n" + formatTaskList(tasks);
    }

    @Tool(description = "Get all todo tasks assigned to a specific person")
    public String getTodosByAssignee(
            @ToolParam(description = "Name of the person the task is assigned to") String assignedTo) {
        List<TodoTask> tasks = todoService.getByAssignee(assignedTo);
        if (tasks.isEmpty()) {
            return "No tasks assigned to " + assignedTo + ".";
        }
        return "Tasks assigned to " + assignedTo + " (" + tasks.size() + "):\n\n" + formatTaskList(tasks);
    }

    @Tool(description = "Create a new todo task. Due date format: yyyy-MM-ddTHH:mm:ss (e.g. 2026-08-05T18:00:00)")
    public String createTodo(
            @ToolParam(description = "Task title") String title,
            @ToolParam(description = "Task description (optional)") String description,
            @ToolParam(description = "Priority: LOW, MEDIUM, HIGH, URGENT") String priority,
            @ToolParam(description = "Due date in format yyyy-MM-ddTHH:mm:ss (optional)") String dueDate,
            @ToolParam(description = "Person assigned to this task (optional)") String assignedTo) {
        log.info("Creating todo: title={}, priority={}", title, priority);
        TodoTask task = todoService.createTask(title, description, priority, dueDate, assignedTo);
        return "Todo task created successfully!\n" + formatTask(task);
    }

    @Tool(description = "Update an existing todo task. Only provide fields you want to change; null/empty fields will be ignored.")
    public String updateTodo(
            @ToolParam(description = "ID of the task to update") Long id,
            @ToolParam(description = "New title (optional)") String title,
            @ToolParam(description = "New status: PENDING, IN_PROGRESS, COMPLETED, CANCELLED (optional)") String status,
            @ToolParam(description = "New priority: LOW, MEDIUM, HIGH, URGENT (optional)") String priority,
            @ToolParam(description = "New due date yyyy-MM-ddTHH:mm:ss (optional)") String dueDate,
            @ToolParam(description = "New description (optional)") String description) {
        log.info("Updating todo: id={}", id);
        TodoTask task = todoService.getById(id);
        if (task == null) {
            return "Todo task with ID " + id + " not found.";
        }
        if (title != null && !title.isBlank()) task.setTitle(title);
        if (status != null && !status.isBlank()) task.setStatus(status.toUpperCase());
        if (priority != null && !priority.isBlank()) task.setPriority(priority.toUpperCase());
        if (description != null && !description.isBlank()) task.setDescription(description);
        if (dueDate != null && !dueDate.isBlank()) {
            try { task.setDueDate(java.time.LocalDateTime.parse(dueDate)); } catch (Exception ignored) {}
        }
        todoService.update(task);
        return "Todo task updated successfully!\n" + formatTask(task);
    }

    @Tool(description = "Delete a todo task by its ID")
    public String deleteTodo(
            @ToolParam(description = "ID of the task to delete") Long id) {
        log.info("Deleting todo: id={}", id);
        TodoTask task = todoService.getById(id);
        if (task == null) {
            return "Todo task with ID " + id + " not found.";
        }
        todoService.deleteById(id);
        return "Todo task deleted: [" + id + "] " + task.getTitle();
    }

    private String formatTaskList(List<TodoTask> tasks) {
        return tasks.stream()
                .map(this::formatTask)
                .collect(Collectors.joining("\n\n"));
    }

    private String formatTask(TodoTask t) {
        return String.format("[%s] %s | Priority: %s | Status: %s | Due: %s | Assigned: %s%n   %s",
                t.getId(),
                t.getTitle(),
                t.getPriority(),
                t.getStatus(),
                t.getDueDate() != null ? t.getDueDate().toString() : "N/A",
                t.getAssignedTo() != null ? t.getAssignedTo() : "Unassigned",
                t.getDescription() != null ? t.getDescription() : "");
    }
}
