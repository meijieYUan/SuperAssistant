package com.itajay.superassistant.controller;

import com.itajay.superassistant.entity.TodoTask;
import com.itajay.superassistant.service.TodoService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService todoService;

    public TodoController(TodoService todoService) {
        this.todoService = todoService;
    }

    @GetMapping
    public List<TodoTask> getAllTodos() {
        return todoService.getAllTasks();
    }

    @GetMapping("/pending")
    public List<TodoTask> getPendingTodos() {
        return todoService.getPendingTasks();
    }

    @GetMapping("/overdue")
    public List<TodoTask> getOverdueTodos() {
        return todoService.getOverdueTasks();
    }

    @PostMapping("/query")
    public List<TodoTask> queryTodos(@RequestBody Map<String, String> params) {
        return todoService.queryTasks(
                params.get("status"),
                params.get("priority"),
                params.get("keyword"));
    }
}