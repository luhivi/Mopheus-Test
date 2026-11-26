package com.example.taskboard.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of an import file.
 *
 * <p>Deliberately not {@link TaskPayload}: that type lists {@code status} among the ignored
 * fields, because interactive clients must not set it. An import is the one path where a stored
 * status has to survive the round trip, so reusing {@code TaskPayload} would silently flatten
 * every imported DONE task back to TODO.
 *
 * <p>Unknown properties are ignored here, unlike on the interactive payloads. The file being
 * imported is by definition an export of this same board, so it carries {@code id},
 * {@code createdAt}, {@code updatedAt}, {@code overdue} and {@code daysOverdue} — the global
 * {@code fail-on-unknown-properties} flag would otherwise reject the app's own export file, row
 * by row, and report a successful import of nothing. The id and timestamps are then discarded in
 * {@code ImportExportService}, which is the behaviour the contract promises.
 *
 * <p>{@code status}, {@code priority} and {@code dueDate} are typed as strings rather than enums
 * and dates on purpose: a bad value must fail <em>that row</em> (counted as skipped), not the
 * whole request, and Jackson would reject the request at bind time for an unknown enum name.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportTaskPayload(
        String title,
        String status,
        String priority,
        String assignee,
        String dueDate,
        java.util.List<String> tags) {
}