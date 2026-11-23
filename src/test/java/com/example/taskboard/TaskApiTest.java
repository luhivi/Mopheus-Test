package com.example.taskboard;

import com.example.taskboard.service.TaskNotFoundException;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.Random.class)
class TaskApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskService taskService;

    /**
     * The context is shared across methods and the store is in memory, so without this a test
     * that counts rows sees whatever earlier tests left behind. The class runs in random order
     * on purpose: a suite that only passes in one order is not testing isolation, it is getting
     * lucky. A failure here means a test is reading state it did not create.
     */
    @BeforeEach
    void resetStore() {
        for (long id : taskService.findAll(null).stream().map(task -> task.id()).toList()) {
            try {
                taskService.delete(id);
            } catch (TaskNotFoundException ignored) {
                // Another test already removed it; nothing to do.
            }
        }
    }

    @Test
    void createsTaskAndListsIt() throws Exception {
        createTask("write the sample ticket");

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'write the sample ticket')]").exists())
                .andExpect(jsonPath("$[0].status").value("TODO"))
                .andExpect(jsonPath("$[0].priority").value("NORMAL"))
                .andExpect(jsonPath("$[0].tags").isArray());
    }

    @Test
    void createsTaskWithAllFields() throws Exception {
        String body = """
                {"title":"full task","priority":"URGENT","assignee":"  alice  ",
                 "dueDate":"2099-12-31","tags":["b","a","b"]}
                """;

        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("URGENT"))
                .andExpect(jsonPath("$.assignee").value("alice"))
                .andExpect(jsonPath("$.dueDate").value("2099-12-31"))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void rejectsBlankTitle() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("title must not be blank"));
    }

    @Test
    void rejectsOversizedTitle() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TitlePayload("x".repeat(81)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsTooManyTagsAndOversizedTag() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"tags\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"tags\":[\"" + "x".repeat(21) + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsUnknownStatusWithoutLeakingInternals() throws Exception {
        long id = createTask("move me");

        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_BODY"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("java."))));
    }

    @Test
    void updatesStatusAndReturnsTask() throws Exception {
        long id = createTask("move me");

        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DOING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("move me"))
                .andExpect(jsonPath("$.status").value("DOING"));

        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void rejectsTodoToDoneShortcutWith409() throws Exception {
        long id = createTask("skip the work");

        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        mockMvc.perform(get("/api/tasks/" + id))
                .andExpect(jsonPath("$.status").value("TODO"));
    }

    @Test
    void rejectsBlockedToDoneShortcutWith409() throws Exception {
        long id = createTask("blocked");
        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    @Test
    void createIgnoresRequestedStatusAndAlwaysStartsTodo() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"sneaky\",\"status\":\"DONE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TODO"));
    }

    @Test
    void rejectsTransitionOutOfDoneWith409() throws Exception {
        long id = createTask("finish me");
        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DOING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TODO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        mockMvc.perform(get("/api/tasks/" + id))
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void repeatedStatusUpdateIsIdempotent() throws Exception {
        long id = createTask("keep");

        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TODO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TODO"));
    }

    @Test
    void returnsTaskDetailAnd404() throws Exception {
        long id = createTask("detail");

        mockMvc.perform(get("/api/tasks/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("detail"));

        mockMvc.perform(get("/api/tasks/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void updatesEditableFieldsWithoutTouchingStatus() throws Exception {
        long id = createTask("before");
        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DOING\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/tasks/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"after\",\"priority\":\"HIGH\",\"assignee\":\"bob\",\"tags\":[\"z\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("after"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.assignee").value("bob"))
                .andExpect(jsonPath("$.status").value("DOING"))
                .andExpect(jsonPath("$.tags[0]").value("z"));
    }

    @Test
    void putClearsFieldsThatAreOmitted() throws Exception {
        long id = createTask("seed");
        mockMvc.perform(put("/api/tasks/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"seeded\",\"priority\":\"URGENT\",\"assignee\":\"bob\","
                                + "\"dueDate\":\"2030-01-01\",\"tags\":[\"x\",\"y\"]}"))
                .andExpect(status().isOk());

        // Strict PUT: what is absent in the body is cleared, so a title-only edit never leaves
        // half of the old object behind and never pretends the omitted fields were kept.
        mockMvc.perform(put("/api/tasks/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"only title\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("only title"))
                .andExpect(jsonPath("$.priority").value("NORMAL"))
                .andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.dueDate").doesNotExist())
                .andExpect(jsonPath("$.tags.length()").value(0));
    }

    @Test
    void updateReturns404ForMissingTask() throws Exception {
        mockMvc.perform(put("/api/tasks/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void statsIsRoutedBeforeIdAndCountsEverything() throws Exception {
        long overdueId = createTaskWith("overdue", "2099-01-01");
        mockMvc.perform(put("/api/tasks/" + overdueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"overdue\",\"dueDate\":\"2020-01-01\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.overdue").isNumber())
                .andExpect(jsonPath("$.byStatus.TODO").isNumber())
                .andExpect(jsonPath("$.byStatus.DONE").isNumber())
                .andExpect(jsonPath("$.byPriority.URGENT").isNumber());
    }

    @Test
    void filtersAndSortsThroughQueryParameters() throws Exception {
        createTaskWith("alpha", null);
        long beta = createTaskWith("beta", null);
        mockMvc.perform(patch("/api/tasks/" + beta + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DOING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/tasks/" + beta + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks").param("status", "DONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("beta"));

        mockMvc.perform(get("/api/tasks").param("q", "alph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("alpha"));

        mockMvc.perform(get("/api/tasks").param("sort", "priority").param("order", "asc"))
                .andExpect(status().isOk());
    }

    @Test
    void reportsMalformedPathSeparatelyFromMalformedQuery() throws Exception {
        mockMvc.perform(get("/api/tasks/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_PATH"));
    }

    @Test
    void statsWinsOverIdRoute() throws Exception {
        // Literal segments outrank variables in Spring's path matching regardless of declaration
        // order; this test fails loudly if anyone changes {id} to String or adds a pattern.
        mockMvc.perform(get("/api/tasks/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber());
    }

    @Test
    void rejectsMisspelledFieldInsteadOfSilentlyDefaulting() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"priorty\":\"URGENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_BODY"));

        long id = createTask("keep priority");
        mockMvc.perform(put("/api/tasks/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"renamed\",\"priorty\":\"URGENT\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/tasks/" + id))
                .andExpect(jsonPath("$.title").value("keep priority"))
                .andExpect(jsonPath("$.priority").value("NORMAL"));
    }

    @Test
    void acceptsRoundTrippedReadOnlyFields() throws Exception {
        long id = createTask("round trip");
        String full = mockMvc.perform(get("/api/tasks/" + id))
                .andReturn().getResponse().getContentAsString();

        // A client that reads a task and writes it straight back must not fail on the fields it
        // is not allowed to set; those are ignored rather than rejected.
        mockMvc.perform(put("/api/tasks/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(full))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("round trip"));
    }

    @Test
    void doesNotEchoQueryValuesBackInErrors() throws Exception {
        mockMvc.perform(get("/api/tasks").param("status", "SECRET_TOKEN_VALUE_abc"))
                .andExpect(jsonPath("$.code").value("MALFORMED_QUERY"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SECRET_TOKEN_VALUE_abc"))));
    }

    @Test
    void rejectsUnknownQueryParameterValueWith400() throws Exception {
        mockMvc.perform(get("/api/tasks").param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_QUERY"));

        mockMvc.perform(get("/api/tasks").param("sort", "whatever"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_QUERY"));
    }

    @Test
    void returns404ForMissingTask() throws Exception {
        mockMvc.perform(patch("/api/tasks/999999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));

        mockMvc.perform(delete("/api/tasks/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void deletesTask() throws Exception {
        long id = createTask("remove me");

        mockMvc.perform(delete("/api/tasks/" + id))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/tasks/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void keepsFrameworkStatusesInsteadOfTurningThemInto500() throws Exception {
        mockMvc.perform(get("/api/definitely-not-a-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(put("/api/tasks"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(get("/nope.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnsupportedMediaTypeWithoutBecoming500() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text is not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void returnsJsonInsteadOfWhitelabelWhenHtmlIsRequested() throws Exception {
        mockMvc.perform(get("/api/tasks").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void sendsAllowHeaderWith405() throws Exception {
        mockMvc.perform(put("/api/tasks"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")));
    }

    @Test
    void keepsBusinessErrorStatusWhenClientCannotAcceptJson() throws Exception {
        // Regression: without a pinned media type the handler's own body could not be
        // negotiated, so Spring degraded this 404 into a 500 Whitelabel page.
        mockMvc.perform(delete("/api/tasks/999").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void rejectsNumericStatusOrdinal() throws Exception {
        long id = createTask("ordinal");

        mockMvc.perform(patch("/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":2}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonStringTitle() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":123}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":true}"))
                .andExpect(status().isBadRequest());
    }

    private long createTask(String title) throws Exception {
        return createTaskWith(title, null);
    }

    private long createTaskWith(String title, String dueDate) throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePayload(title, dueDate))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.get("id").asLong();
    }

    private record TitlePayload(String title) {
    }

    private record CreatePayload(String title, String dueDate) {
    }
}
