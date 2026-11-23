package com.example.taskboard.model;

import java.time.Instant;

public record Task(long id, String title, TaskStatus status, Instant createdAt) {
}
