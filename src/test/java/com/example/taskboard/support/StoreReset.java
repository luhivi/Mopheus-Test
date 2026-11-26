package com.example.taskboard.support;

import jakarta.persistence.EntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;

/** Shared scaffolding for the database-backed tests: a pinnable clock and a clean board. */
public final class StoreReset {

    private StoreReset() {
    }

    @TestConfiguration
    public static class ClockConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    /**
     * Empties both tables and restarts the identity counters.
     *
     * <p>Rollback is not enough here: H2 identity sequences are not transactional, so ids would
     * keep climbing across methods and tests that pin {@code id == 1} would pass only once. The
     * delete order respects the tag table's foreign key to tasks.
     */
    @SuppressWarnings("unchecked")
    public static void wipe(EntityManager entityManager, TransactionTemplate transactionTemplate) {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("delete from task_tags").executeUpdate();
            entityManager.createNativeQuery("delete from task_activities").executeUpdate();
            entityManager.createNativeQuery("delete from tasks").executeUpdate();
            entityManager.createNativeQuery("alter table tasks alter column id restart with 1").executeUpdate();
            entityManager.createNativeQuery("alter table task_activities alter column id restart with 1").executeUpdate();
        });
    }
}