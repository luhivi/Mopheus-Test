package com.example.taskboard.web;

import com.example.taskboard.model.TaskStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(
        @NotNull(message = "status must not be null") TaskStatus status) {
}
