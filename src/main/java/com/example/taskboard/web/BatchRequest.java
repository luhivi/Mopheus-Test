package com.example.taskboard.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body for {@code POST /api/tasks/batch-status} and {@code POST /api/tasks/batch-delete}.
 *
 * <p>{@code ids} is a raw {@link JsonNode} and {@code status} a {@link String} rather than
 * {@code List<Long>} / {@code TaskStatus} on purpose. Both wrong shapes would otherwise be handled
 * by Jackson (as MALFORMED_BODY) or by bean validation, and the batch contract promises a single
 * request-level answer: 400 {@code VALIDATION_FAILED} with a message naming the problem. Parsing
 * here keeps that promise and lets element 3 of a 200-element array be diagnosed by name.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record BatchRequest(JsonNode ids, String status) {
}