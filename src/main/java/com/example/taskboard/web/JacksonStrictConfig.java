package com.example.taskboard.web;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rejects input that Jackson would otherwise coerce silently.
 *
 * <p>The API contract says {@code title} is a string and {@code status} is one of four
 * enum names (TODO, DOING, BLOCKED, DONE). By default Jackson accepts {@code {"title": 123}} as {@code "123"} and
 * {@code {"status": 2}} as the second enum constant, so the server would honour a
 * document the contract does not define. Both are rejected before any handler runs.
 */
@Configuration
public class JacksonStrictConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictScalars() {
        return builder -> builder.postConfigurer(mapper -> mapper
                .coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
