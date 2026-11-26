package com.example.taskboard.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body of {@code POST /api/tasks/import}.
 *
 * <p>{@code tasks} stays a {@link JsonNode} for the same reason batch {@code ids} does: a
 * request-level problem (missing, not an array, too many rows) must be one 400, while a bad value
 * *inside* a row must skip that row only. A typed list would let Jackson reject the whole request
 * on the first malformed row.
 *
 * <p>{@code exportedAt} and {@code count} are ignored so the export file can be posted back
 * verbatim. Those two keys are written by {@code GET /export} and are meaningless on the way in,
 * but the global {@code fail-on-unknown-properties} setting would otherwise turn the app's own
 * backup into a 400 — the one input an import endpoint must always accept.
 */
@JsonIgnoreProperties({"exportedAt", "count"})
public record ImportRequest(String mode, String confirm, JsonNode tasks) {
}