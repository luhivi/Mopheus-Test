package com.example.taskboard.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One row of a batch answer.
 *
 * <p>Both success and failure share a type because they share an array, and the client walks that
 * array position by position. A success carries {@code task} (for status changes) and no error
 * fields; a failure carries {@code code}/{@code message} and no task. {@code NON_NULL} turns the
 * absent half into absent keys instead of explicit nulls, so a client's
 * {@code if (item.task)} check is correct.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchResultItem(
        long id,
        boolean ok,
        TaskResponse task,
        String code,
        String message) {

    public static BatchResultItem success(long id, TaskResponse task) {
        return new BatchResultItem(id, true, task, null, null);
    }

    public static BatchResultItem success(long id) {
        return new BatchResultItem(id, true, null, null, null);
    }

    public static BatchResultItem failure(long id, String code, String message) {
        return new BatchResultItem(id, false, null, code, message);
    }
}