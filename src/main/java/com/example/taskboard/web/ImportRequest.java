package com.example.taskboard.web;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body of {@code POST /api/tasks/import}.
 *
 * <p>{@code tasks} stays a {@link JsonNode} for the same reason batch {@code ids} does: a
 * request-level problem (missing, not an array, too many rows) must be one 400, while a bad value
 * *inside* a row must skip that row only. A typed list would let Jackson reject the whole request
 * on the first malformed row.
 */
public record ImportRequest(String mode, String confirm, JsonNode tasks) {
}