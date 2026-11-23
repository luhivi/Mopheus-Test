package com.example.taskboard.model;

import java.util.Set;

/**
 * Task lifecycle. {@link #DONE} is terminal: once a task is finished, moving it back would
 * silently undo work that other people may already have seen as complete. Every other move is
 * allowed, because a board that refuses to move a card back to TODO is a board people stop using.
 */
public enum TaskStatus {
    TODO,
    DOING,
    BLOCKED,
    DONE;

    private static final Set<TaskStatus> ALL = Set.of(values());

    public boolean canMoveTo(TaskStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            // Re-sending the current status is a no-op, not an error: drag-and-drop, retries and
            // a stale second tab all produce it, and none of them are user mistakes.
            return true;
        }
        return this != DONE && ALL.contains(target);
    }

    public boolean isTerminal() {
        return this == DONE;
    }
}
