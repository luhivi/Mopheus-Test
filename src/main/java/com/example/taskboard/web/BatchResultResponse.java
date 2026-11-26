package com.example.taskboard.web;

import com.example.taskboard.service.BatchOutcome;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wire shape of one batch row.
 *
 * <p>Built as a map rather than a record so a failed row omits {@code task} entirely and a
 * successful delete omits both {@code task} and the error fields, exactly as the frontend parses
 * them. A record with nullable members would emit {@code "task":null} / {@code "code":null},
 * which is a different document than the contract describes.
 */
public final class BatchResultResponse {

    private BatchResultResponse() {
    }

    public static Map<String, Object> of(BatchOutcome outcome, LocalDate today, boolean includeTask) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", outcome.id());
        body.put("ok", outcome.ok());
        if (!outcome.ok()) {
            body.put("code", outcome.code());
            body.put("message", outcome.message());
            return body;
        }
        if (includeTask && outcome.task() != null) {
            body.put("task", TaskResponse.from(outcome.task(), today));
        }
        return body;
    }
}