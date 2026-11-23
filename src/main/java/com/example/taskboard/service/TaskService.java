package com.example.taskboard.service;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TaskService {

    private final ConcurrentMap<Long, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Clock clock;

    public TaskService(Clock clock) {
        this.clock = clock;
    }

    public List<Task> findAll() {
        return tasks.values().stream()
                .sorted(Comparator.comparingLong(Task::id).reversed())
                .toList();
    }

    public Task create(String title) {
        long id = sequence.incrementAndGet();
        Task task = new Task(id, title, TaskStatus.TODO, Instant.now(clock));
        tasks.put(id, task);
        return task;
    }

    public Task updateStatus(long id, TaskStatus status) {
        Task updated = tasks.computeIfPresent(id,
                (key, current) -> new Task(current.id(), current.title(), status, current.createdAt()));
        if (updated == null) {
            throw new TaskNotFoundException(id);
        }
        return updated;
    }

    public void delete(long id) {
        if (tasks.remove(id) == null) {
            throw new TaskNotFoundException(id);
        }
    }
}
