package com.example.taskboard.web;

import java.util.List;

public record BatchResponse(List<BatchResultItem> results) {
}