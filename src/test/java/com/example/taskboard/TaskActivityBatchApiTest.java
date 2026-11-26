package com.example.taskboard;

import com.example.taskboard.model.Task;
import com.example.taskboard.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Activities and batch operations, at the wire level.
 *
 * <p>Shares the store with the other context tests, so it clears tasks first for the same reason
 * {@code TaskApiTest} does: counting rows against leftover state is how a suite ends up passing
 * only in one order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.Random.class)
class TaskActivityBatchApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskService taskService;

    @BeforeEach
    void resetStore() {
        for (long id : taskService.findAll(null).stream().map(Task::id).toList()) {
            try {
                taskService.delete(id);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
    }

    @Test
    void createWritesOneCreatedActivity() throws Exception {
        long id = createTask("watch me");

        mockMvc.perform(get("/api/tasks/" + id + "/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].taskId").value(id))
                .andExpect(jsonPath("$[0].type").value("CREATED"))
                .andExpect(jsonPath("$[0].detail").value("创建任务"))
                .andExpect(jsonPath("$[0].createdAt").value(matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")));
    }

    @Test
    void statusChangeIsLoggedWithAnArrow() throws Exception {
        long id = createTask("move");

        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DOING\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/" + id + "/activities"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("STATUS_CHANGED"))
                .andExpect(jsonPath("$[0].detail").value("TODO → DOING"));
    }

    @Test
    void sameStatusPatchLogsNothingAndKeepsUpdatedAt() throws Exception {
        long id = createTask("stay");
        String before = mockMvc.perform(get("/api/tasks/" + id))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TODO\"}"))
                .andExpect(status().isOk());

        String after = mockMvc.perform(get("/api/tasks/" + id))
                .andReturn().getResponse().getContentAsString();

        // Anti-distortion assertion 1: an idempotent no-op must not move updatedAt, or the board
        // looks edited and every "modified" indicator is wrong.
        assertEqualsJson(objectMapper.readTree(before).get("updatedAt"),
                objectMapper.readTree(after).get("updatedAt"));
        mockMvc.perform(get("/api/tasks/" + id + "/activities"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("CREATED"));
    }

    @Test
    void putThatChangesNothingLogsNoUpdateActivity() throws Exception {
        long id = createTask("unchanged");
        String full = mockMvc.perform(get("/api/tasks/" + id))
                .andReturn().getResponse().getContentAsString();

        // Sending the object back verbatim is the "no edit" case. An UPDATED row here would be a
        // lie that also duplicates every field the client happened to round-trip.
        mockMvc.perform(put("/api/tasks/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(full))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/" + id + "/activities"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void putWithRealEditsListsOnlyChangedFieldsInChinese() throws Exception {
        long id = createTask("before edit");

        mockMvc.perform(put("/api/tasks/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"after edit\",\"priority\":\"HIGH\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/" + id + "/activities"))
                .andExpect(jsonPath("$[0].type").value("UPDATED"))
                .andExpect(jsonPath("$[0].detail").value("标题、优先级"));
    }

    @Test
    void deletingTaskKeepsItsActivitiesIncludingDeletedRow() throws Exception {
        long id = createTask("remove me");
        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DOING\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/tasks/" + id)).andExpect(status().isNoContent());

        // The task is gone but its history is not: this is the audit trail, and a cascade here
        // would erase the only record that the task existed.
        mockMvc.perform(get("/api/tasks/" + id + "/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("DELETED"))
                .andExpect(jsonPath("$[0].detail").value("删除任务"))
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(get("/api/tasks/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void activitiesForUnknownTaskIs404() throws Exception {
        mockMvc.perform(get("/api/tasks/999999/activities"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void activitiesAreOrderedNewestFirst() throws Exception {
        long id = createTask("sequence");
        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DOING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/" + id + "/activities"))
                .andExpect(jsonPath("$[0].detail").value("DOING → BLOCKED"))
                .andExpect(jsonPath("$[1].detail").value("TODO → DOING"))
                .andExpect(jsonPath("$[2].type").value("CREATED"));
    }

    @Test
    void activitiesRespectTheLimit() throws Exception {
        long id = createTask("many");
        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DOING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/" + id + "/activities").param("limit", "2"))
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/tasks/" + id + "/activities").param("limit", "200"))
                .andExpect(status().isOk());
    }

    @Test
    void malformedLimitIs400MALFORMEDQUERY() throws Exception {
        long id = createTask("limit");

        for (String bad : List.of("abc", "0", "-1", "201", "1.5", "")) {
            mockMvc.perform(get("/api/tasks/" + id + "/activities").param("limit", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_QUERY"));
        }
    }

    @Test
    void limitErrorDoesNotEchoTheValue() throws Exception {
        long id = createTask("echo");

        mockMvc.perform(get("/api/tasks/" + id + "/activities").param("limit", "SECRET_LIMIT_TOKEN"))
                .andExpect(jsonPath("$.code").value("MALFORMED_QUERY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET_LIMIT_TOKEN"))));
    }

    @Test
    void batchStatusReturnsResultsInRequestOrderAfterDeduplication() throws Exception {
        long first = createTask("first");
        long second = createTask("second");

        String body = mockMvc.perform(post("/api/tasks/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + second + "," + first + "," + second + "],\"status\":\"DOING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].id").value(second))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[0].task.status").value("DOING"))
                .andExpect(jsonPath("$.results[0].task.title").value("second"))
                .andExpect(jsonPath("$.results[0].task.tags").isArray())
                .andExpect(jsonPath("$.results[0].task.overdue").isBoolean())
                .andExpect(jsonPath("$.results[1].id").value(first))
                .andReturn().getResponse().getContentAsString();

        // The duplicate must appear once, and the dedup order must be the request order — not
        // sorted, not insertion order of some internal map.
        JsonNode results = objectMapper.readTree(body).get("results");
        assertEqualsJsonText(second, results.get(0).get("id"));
        assertEqualsJsonText(first, results.get(1).get("id"));
    }

    @Test
    void batchStatusIsPerRowSoOneFailureDoesNotRollBackTheRest() throws Exception {
        long movable = createTask("movable");
        long finished = createTask("finished");
        mockMvc.perform(patch("/api/tasks/" + finished + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DOING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/tasks/" + finished + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tasks/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + movable + "," + finished + ",999999],\"status\":\"DOING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[1].ok").value(false))
                .andExpect(jsonPath("$.results[1].code").value("INVALID_TRANSITION"))
                .andExpect(jsonPath("$.results[1].task").doesNotExist())
                .andExpect(jsonPath("$.results[2].ok").value(false))
                .andExpect(jsonPath("$.results[2].code").value("TASK_NOT_FOUND"));

        // The successful row really committed: a shared transaction would have rolled it back.
        mockMvc.perform(get("/api/tasks/" + movable))
                .andExpect(jsonPath("$.status").value("DOING"));
    }

    @Test
    void batchDeleteOmitsTaskOnSuccess() throws Exception {
        long keep = createTask("keep");
        long drop = createTask("drop");

        mockMvc.perform(post("/api/tasks/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + drop + ",999999]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value(drop))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[0].task").doesNotExist())
                .andExpect(jsonPath("$.results[1].ok").value(false))
                .andExpect(jsonPath("$.results[1].code").value("TASK_NOT_FOUND"));

        mockMvc.perform(get("/api/tasks/" + drop)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/" + keep)).andExpect(status().isOk());
    }

    @Test
    void batchDeleteLogsDeletedActivityForEachRow() throws Exception {
        long id = createTask("delete logged");

        mockMvc.perform(post("/api/tasks/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id + "]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/" + id + "/activities"))
                .andExpect(jsonPath("$[0].type").value("DELETED"));
    }

    @Test
    void batchRequestLevelFailuresAre400ValidationFailed() throws Exception {
        // Empty ids, missing status, unknown status: all rejected before any row runs.
        mockMvc.perform(post("/api/tasks/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[],\"status\":\"DOING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/tasks/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/tasks/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"status\":\"NOPE\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/tasks/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void batchRejectsMoreThan200Ids() throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 201; i++) {
            if (i > 1) {
                ids.append(',');
            }
            ids.append(i);
        }

        mockMvc.perform(post("/api/tasks/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void batchRequestShapeErrorsAllReportValidationFailed() throws Exception {
        // Every malformed *request* shape must land on the one code the frontend handles for
        // batches. Binding these with @Valid would have answered MALFORMED_BODY instead, because
        // Jackson fails before validation runs.
        for (String body : List.of(
                "{\"ids\":\"oops\",\"status\":\"DOING\"}",
                "{\"ids\":[1],\"status\":\"NOPE\"}",
                "{\"ids\":[\"1\"],\"status\":\"DOING\"}",
                "{\"ids\":[null],\"status\":\"DOING\"}",
                "{\"status\":\"DOING\"}",
                "{\"ids\":[1]}",
                "{\"ids\":[],\"status\":\"DOING\"}",
                "{}",
                "[]")) {
            mockMvc.perform(post("/api/tasks/batch-status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void batchStatusAcceptsLowercaseEnumToMatchTheFilterParsers() throws Exception {
        long id = createTask("lowercase");

        mockMvc.perform(post("/api/tasks/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id + "],\"status\":\"doing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].task.status").value("DOING"));
    }

    @Test
    void exportSetsTheContractFilenameAndPaylods() throws Exception {
        createTask("exported one");

        mockMvc.perform(get("/api/tasks/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        matchesPattern("attachment; filename=\"taskboard-export-\\d{8}-\\d{6}\\.json\"")))
                .andExpect(jsonPath("$.exportedAt").value(matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")))
                .andExpect(jsonPath("$.count").isNumber())
                .andExpect(jsonPath("$.tasks").isArray())
                .andExpect(jsonPath("$.tasks[0].title").exists())
                .andExpect(jsonPath("$.tasks[0].status").exists())
                // "全字段" is asserted field by field: an export missing the computed fields
                // would force every consumer to special-case the file format.
                .andExpect(jsonPath("$.tasks[0].id").exists())
                .andExpect(jsonPath("$.tasks[0].priority").exists())
                .andExpect(jsonPath("$.tasks[0].tags").isArray())
                .andExpect(jsonPath("$.tasks[0].createdAt").exists())
                .andExpect(jsonPath("$.tasks[0].updatedAt").exists())
                .andExpect(jsonPath("$.tasks[0].overdue").isBoolean())
                .andExpect(jsonPath("$.tasks[0].daysOverdue").isNumber());
    }

    @Test
    void exportIsParseableAsJsonContentType() throws Exception {
        mockMvc.perform(get("/api/tasks/export"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    private long createTask(String title) throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePayload(title))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private static void assertEqualsJson(JsonNode expected, JsonNode actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    private static void assertEqualsJsonText(long expected, JsonNode actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual.asLong());
    }

    private record CreatePayload(String title) {
    }
}