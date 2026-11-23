package com.example.taskboard.service;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(long id) {
        super("Task " + id + " does not exist");
    }
}
