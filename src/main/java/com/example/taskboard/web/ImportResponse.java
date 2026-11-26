package com.example.taskboard.web;

public record ImportResponse(String mode, int imported, int skipped, long total) {
}