package com.example.taskboard.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Lets a test move time forward without sleeping, and pin it again between methods. Tests share
 * one instance because the service is now a real Spring bean; without {@link #reset} the second
 * test would inherit the first one's advanced clock.
 */
public final class MutableClock extends Clock {

    private volatile Instant current;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.current = start;
        this.zone = zone;
    }

    public void reset(Instant instant) {
        this.current = instant;
    }

    public void advance(Duration amount) {
        current = current.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(current, newZone);
    }

    @Override
    public Instant instant() {
        return current;
    }
}