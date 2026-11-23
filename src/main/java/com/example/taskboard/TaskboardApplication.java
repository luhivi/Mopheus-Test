package com.example.taskboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
public class TaskboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskboardApplication.class, args);
    }

    /**
     * Business dates (overdue, due today) are local dates, so the clock must agree with the dates
     * users type into the form. A UTC clock would call a task due "tomorrow" for anyone east of
     * Greenwich from local midnight until the UTC day turns over — up to eight hours a day here.
     */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
