package com.example.taskboard.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Task lifecycle.
 *
 * <p>The allowed moves are an explicit table rather than a rule like "anything except out of
 * DONE". A rule that only guarded DONE would still allow TODO to jump straight to DONE, which
 * is the one shortcut this board exists to prevent: a task that was never started has not been
 * finished. Every consumer — the Java service and the browser — must read this same table, so it
 * is spelled out here instead of being derived from enum ordering.
 */
public enum TaskStatus {
    TODO,
    DOING,
    BLOCKED,
    DONE;

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = buildTable();

    private static Map<TaskStatus, Set<TaskStatus>> buildTable() {
        Map<TaskStatus, Set<TaskStatus>> table = new EnumMap<>(TaskStatus.class);
        table.put(TODO, EnumSet.of(DOING, BLOCKED));
        table.put(DOING, EnumSet.of(TODO, BLOCKED, DONE));
        table.put(BLOCKED, EnumSet.of(TODO, DOING));
        table.put(DONE, Collections.emptySet());
        return Collections.unmodifiableMap(table);
    }

    public boolean canMoveTo(TaskStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            // Re-sending the current status is a no-op, not an error: drag-and-drop, retries and
            // a stale second tab all produce it, and none of them are user mistakes.
            return true;
        }
        return ALLOWED.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    public Set<TaskStatus> allowedTargets() {
        return ALLOWED.getOrDefault(this, Collections.emptySet());
    }

    public boolean isTerminal() {
        return this == DONE;
    }
}
