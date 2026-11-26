package com.example.taskboard.service;

/** Result of an import: what was written, what was rejected, and the size of the board after it. */
public record ImportOutcome(String mode, int imported, int skipped, long total) {
}