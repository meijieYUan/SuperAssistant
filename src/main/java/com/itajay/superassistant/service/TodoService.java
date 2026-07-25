package com.itajay.superassistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.itajay.superassistant.entity.TodoTask;
import com.itajay.superassistant.mapper.TodoTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TodoService {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);
    private final TodoTaskMapper todoTaskMapper;

    public TodoService(TodoTaskMapper todoTaskMapper) {
        this.todoTaskMapper = todoTaskMapper;
    }

    public List<TodoTask> queryTasks(String status, String priority, String keyword) {
        // Normalize status/priority to uppercase
        if (status != null && !status.isBlank()) {
            status = status.toUpperCase();
        } else {
            status = null;
        }
        if (priority != null && !priority.isBlank()) {
            priority = priority.toUpperCase();
        } else {
            priority = null;
        }
        if (keyword != null && keyword.isBlank()) {
            keyword = null;
        }
        return todoTaskMapper.searchTasks(status, priority, keyword);
    }

    public List<TodoTask> getAllTasks() {
        return todoTaskMapper.selectList(null);
    }

    public List<TodoTask> getPendingTasks() {
        return todoTaskMapper.selectList(
                new LambdaQueryWrapper<TodoTask>().eq(TodoTask::getStatus, "PENDING"));
    }

    public List<TodoTask> getOverdueTasks() {
        return todoTaskMapper.findOverdue("PENDING", LocalDateTime.now());
    }

    public List<TodoTask> getByPriority(String priority) {
        return todoTaskMapper.selectList(
                new LambdaQueryWrapper<TodoTask>().eq(TodoTask::getPriority, priority.toUpperCase()));
    }

    public List<TodoTask> getByAssignee(String assignedTo) {
        return todoTaskMapper.selectList(
                new LambdaQueryWrapper<TodoTask>().eq(TodoTask::getAssignedTo, assignedTo));
    }

    public void save(TodoTask task) {
        todoTaskMapper.insert(task);
    }
}