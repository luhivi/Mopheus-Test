package com.example.taskboard.model;

/**
 * What happened to a task.
 *
 * <p>The set is deliberately closed and mirrors the four write paths: creation, a field edit, a
 * status move, and deletion. {@code DELETED} exists because the activity row outlives the task row
 * — without it, a deleted task's history would end on whatever its last change was, and the one
 * fact an investigation always needs ("who removed it") would be missing.
 */
public enum TaskActivityType {
    CREATED,
    UPDATED,
    STATUS_CHANGED,
    DELETED
}