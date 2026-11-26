package com.example.taskboard.web;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body of {@code POST /api/tasks/import}.
 *
 * <p>{@code tasks} stays a {@link JsonNode} so the endpoint can tell "missing" and "not an array"
 * (both 400 {@code VALIDATION_FAILED}) apart from "an array with some bad rows" (200 with a
 * {@code skipped} count). Binding it to {@code List<ImportTaskPayload>} would fold every malformed
 * row into one all-or-nothing deserialisation error.
 */
public record ImportRequest(
        String mode,
        String confirm,
        JsonNode tasks) {
}