package com.example.taskboard.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = 80, message = "title must be at most 80 characters")
        String title) {
}
