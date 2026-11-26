package com.example.taskboard;

import com.example.taskboard.model.Task;
import com.example.taskboard.model.TaskActivityType;
import com.example.taskboard.model.TaskPriority;
import com.example.taskboard.model.TaskStatus;
import com.example.taskboard.persistence.TaskActivityRepository;
import com.example.taskboard.persistence.TaskRepository;
import com.example.taskboard.service.TaskService;
import com.example.taskboard.support.MutableClock;
import com.example.taskboard.support.StoreReset;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four new capabilities, asserted at the HTTP boundary the browser actually sees.
 *
 * <p>Kept in its own class rather than appended to {@code TaskApiTest} so the existing 35 contract
 * cases stay a readable, untouched baseline: a failure here means a new feature broke, a failure
 * there means the old contract did.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StoreReset.ClockConfig.class)
class TaskFeatureApiTest {

    private static final String BASE = "/api/tasks";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskActivityRepository activityRepository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetStore() {
        clock.reset(Instant.parse("2026-01-01T00:00:00Z"));
        StoreReset.wipe(entityManager, transactionTemplate);
    }

    private long createTask(String title) throws Exception {
        String body = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectId(body);
    }

    private long objectId(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("id").asLong();
    }

    private void moveTo(long id, String status) throws Exception {
        mockMvc.perform(patch(BASE + "/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    /* ------------------------------------------------------------------ activities */

    @Test
    void activitiesRecordCreateEditAndStatusChangeInReverseOrder() throws Exception {
        long id = createTask("audit me");
        clock.advance(Duration.ofSeconds(1));
        mockMvc.perform(put(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"audited\",\"assignee\":\"alice\"}"))
                .andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(1));
        moveTo(id, "DOING");

        mockMvc.perform(get(BASE + "/" + id + "/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].type").value("STATUS_CHANGED"))
                .andExpect(jsonPath("$[0].detail").value("TODO → DOING"))
                .andExpect(jsonPath("$[0].taskId").value(id))
                .andExpect(jsonPath("$[1].type").value("UPDATED"))
                .andExpect(jsonPath("$[1].detail").value("修改字段：标题、负责人"))
                .andExpect(jsonPath("$[2].type").value("CREATED"))
                .andExpect(jsonPath("$[2].detail").value("创建任务"));
    }

    @Test
    void sameStatusPatchWritesNoActivity() throws Exception {
        long id = createTask("stay");

        mockMvc.perform(patch(BASE + "/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TODO\"}"))
                .andExpect(status().isOk());

        // Only the CREATED line: an idempotent no-op must not show up as a status change.
        mockMvc.perform(get(BASE + "/" + id + "/activities"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("CREATED"));
    }

    @Test
    void putWithNoEffectiveChangeWritesNoActivityAndKeepsUpdatedAt() throws Exception {
        long id = createTask("unchanged");
        String before = mockMvc.perform(get(BASE + "/" + id))
                .andReturn().getResponse().getContentAsString();

        clock.advance(Duration.ofHours(1));
        // Same values, but padded and duplicated: normalisation must see no difference.
        mockMvc.perform(put(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  unchanged  \",\"priority\":\"NORMAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").value(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(before).get("updatedAt").asText()));

        mockMvc.perform(get(BASE + "/" + id + "/activities"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("CREATED"));
    }

    @Test
    void deletedTaskKeepsItsHistoryAndIsStillReadable() throws Exception {
        long id = createTask("remove me");

        mockMvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());

        // The task row is gone...
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));

        // ...but the history, including who-removed-it, still answers.
        mockMvc.perform(get(BASE + "/" + id + "/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("DELETED"))
                .andExpect(jsonPath("$[0].detail").value("删除任务"))
                .andExpect(jsonPath("$[1].type").value("CREATED"));
    }

    @Test
    void activitiesForUnknownTaskIs404() throws Exception {
        mockMvc.perform(get(BASE + "/999/activities"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void activitiesLimitIsValidatedAndApplied() throws Exception {
        long id = createTask("limited");
        moveTo(id, "DOING");
        moveTo(id, "DONE");

        mockMvc.perform(get(BASE + "/" + id + "/activities").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("STATUS_CHANGED"));

        for (String bad : new String[]{"0", "-1", "201", "abc", "1.5"}) {
            mockMvc.perform(get(BASE + "/" + id + "/activities").param("limit", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_QUERY"));
        }

        mockMvc.perform(get(BASE + "/" + id + "/activities").param("limit", "200"))
                .andExpect(status().isOk());
    }

    @Test
    void activityTimestampIsIsoUtcWithAnInstantShape() throws Exception {
        long id = createTask("stamp");

        MvcResult result = mockMvc.perform(get(BASE + "/" + id + "/activities"))
                .andExpect(status().isOk())
                .andReturn();
        String createdAt = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get(0).get("createdAt").asText();

        // Instant.toString() shape: yyyy-MM-ddTHH:mm:ss[.fraction]Z. This is the format the
        // front end feeds straight into new Date().
        assertTrue(createdAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"),
                "unexpected Instant wire shape: " + createdAt);
        assertTrue(createdAt.endsWith("Z"), "Instant must be UTC: " + createdAt);
    }

    /* --------------------------------------------------------------------- batch */

    @Test
    void batchStatusReportsEachRowIndependentlyInRequestOrder() throws Exception {
        long a = createTask("a");
        long b = createTask("b");
        long c = createTask("c");

        // c is moved to DONE first, so a TODO->DONE attempt on it must fail on its own.
        moveTo(c, "DOING");
        moveTo(c, "DONE");

        String body = "{\"ids\":[" + b + "," + a + ",999," + c + "," + b + "],\"status\":\"DOING\"}";
        mockMvc.perform(post(BASE + "/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // Duplicate b collapses to its first position: order is request order, deduped.
                .andExpect(jsonPath("$.results.length()").value(4))
                .andExpect(jsonPath("$.results[0].id").value(b))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[0].task.status").value("DOING"))
                // Success rows carry the full TaskResponse, not a reduced shape.
                .andExpect(jsonPath("$.results[0].task.title").value("b"))
                .andExpect(jsonPath("$.results[0].task.overdue").value(false))
                .andExpect(jsonPath("$.results[1].id").value(a))
                .andExpect(jsonPath("$.results[1].ok").value(true))
                .andExpect(jsonPath("$.results[2].ok").value(false))
                .andExpect(jsonPath("$.results[2].code").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.results[2].task").doesNotExist())
                .andExpect(jsonPath("$.results[3].id").value(c))
                .andExpect(jsonPath("$.results[3].ok").value(false))
                .andExpect(jsonPath("$.results[3].code").value("INVALID_TRANSITION"));

        // The successful rows really were written: a partial failure does not roll the batch back.
        mockMvc.perform(get(BASE + "/" + a)).andExpect(jsonPath("$.status").value("DOING"));
        mockMvc.perform(get(BASE + "/" + b)).andExpect(jsonPath("$.status").value("DOING"));
    }

    @Test
    void batchStatusRejectsBadRequestsWith400() throws Exception {
        long id = createTask("t");

        String[] badBodies = {
                "{\"status\":\"DOING\"}",
                "{\"ids\":\"1\",\"status\":\"DOING\"}",
                "{\"ids\":{},\"status\":\"DOING\"}",
                "{\"ids\":[],\"status\":\"DOING\"}",
                "{\"ids\":[\"1\"],\"status\":\"DOING\"}",
                "{\"ids\":[1.5],\"status\":\"DOING\"}",
                "{\"ids\":[" + id + "]}",
                "{\"ids\":[" + id + "],\"status\":\"NOPE\"}",
                "{\"ids\":[" + id + "],\"status\":\"\"}"
        };
        for (String body : badBodies) {
            mockMvc.perform(post(BASE + "/batch-status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void batchStatusRejectsMoreThanTwoHundredUniqueIds() throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 201; i++) {
            if (i > 1) {
                ids.append(',');
            }
            ids.append(i);
        }
        mockMvc.perform(post(BASE + "/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + ids + "],\"status\":\"DOING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 201 entries that are only 200 unique ids is inside the limit once deduplicated.
        mockMvc.perform(post(BASE + "/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + ids + ",1],\"status\":\"DOING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        StringBuilder exactly200 = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            if (i > 1) {
                exactly200.append(',');
            }
            exactly200.append(i);
        }
        mockMvc.perform(post(BASE + "/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + exactly200 + ",1],\"status\":\"DOING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(200));
    }

    @Test
    void batchDeleteReportsEachRowAndKeepsHistory() throws Exception {
        long a = createTask("a");
        long b = createTask("b");

        mockMvc.perform(post(BASE + "/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + a + "," + b + ",999]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].id").value(a))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                // A deleted task has no resource to return, so no `task` key at all.
                .andExpect(jsonPath("$.results[0].task").doesNotExist())
                .andExpect(jsonPath("$.results[1].ok").value(true))
                .andExpect(jsonPath("$.results[2].ok").value(false))
                .andExpect(jsonPath("$.results[2].code").value("TASK_NOT_FOUND"));

        mockMvc.perform(get(BASE + "/" + a + "/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("DELETED"));
    }

    /* ---------------------------------------------------------------- import/export */

    @Test
    void exportReturnsDownloadHeadersAndEveryField() throws Exception {
        long id = createTask("export me");
        mockMvc.perform(put(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"export me\",\"priority\":\"HIGH\",\"assignee\":\"alice\","
                                + "\"dueDate\":\"2026-12-31\",\"tags\":[\"a\",\"b\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE + "/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.matchesPattern(
                                "attachment; filename=\"taskboard-export-\\d{8}-\\d{6}\\.json\"")))
                .andExpect(jsonPath("$.exportedAt").isString())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].title").value("export me"))
                .andExpect(jsonPath("$.tasks[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.tasks[0].assignee").value("alice"))
                .andExpect(jsonPath("$.tasks[0].dueDate").value("2026-12-31"))
                .andExpect(jsonPath("$.tasks[0].tags[0]").value("a"))
                .andExpect(jsonPath("$.tasks[0].createdAt").isString())
                .andExpect(jsonPath("$.tasks[0].overdue").isBoolean())
                .andExpect(jsonPath("$.tasks[0].daysOverdue").isNumber());
    }

    @Test
    void exportRouteWinsOverIdRoute() throws Exception {
        // `export` is a literal segment; like `stats`, it must not be parsed as an id.
        mockMvc.perform(get(BASE + "/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void importMergeAppendsWithNewIdsAndCountsSkippedRows() throws Exception {
        createTask("existing");

        String body = "{\"mode\":\"merge\",\"tasks\":["
                + "{\"title\":\"good one\",\"status\":\"DOING\",\"priority\":\"HIGH\","
                + "\"assignee\":\"bob\",\"dueDate\":\"2026-12-31\",\"tags\":[\"a\",\"b\"]},"
                // whitespace-only title -> skipped
                + "{\"title\":\"   \",\"status\":\"TODO\"},"
                // unknown status -> skipped
                + "{\"title\":\"bad status\",\"status\":\"NOPE\"},"
                // unparseable date -> skipped
                + "{\"title\":\"bad date\",\"dueDate\":\"2026-13-45\"},"
                // six tags after normalisation -> skipped
                + "{\"title\":\"too many tags\",\"tags\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]},"
                // duplicate/blank tags collapse to two, both within limits -> imported
                + "{\"title\":\"deduped tags\",\"tags\":[\" x \",\"x\",\"\",\"y\"]}"
                + "]}";

        mockMvc.perform(post(BASE + "/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("merge"))
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(4))
                // total is the board size after import: 1 existing + 2 imported.
                .andExpect(jsonPath("$.total").value(3));

        // Imported rows get fresh ids, and the supplied status is honoured (not forced to TODO).
        mockMvc.perform(get(BASE).param("q", "good one"))
                .andExpect(jsonPath("$[0].status").value("DOING"))
                .andExpect(jsonPath("$[0].priority").value("HIGH"))
                .andExpect(jsonPath("$[0].assignee").value("bob"));

        mockMvc.perform(get(BASE).param("q", "deduped tags"))
                .andExpect(jsonPath("$[0].tags.length()").value(2))
                .andExpect(jsonPath("$[0].tags[0]").value("x"))
                .andExpect(jsonPath("$[0].tags[1]").value("y"));
    }

    @Test
    void importAllowsDirectJumpToAnyLegalStatus() throws Exception {
        // Import is the restore path, so it is not bound by the interactive transition table.
        mockMvc.perform(post(BASE + "/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"merge\",\"tasks\":[{\"title\":\"done import\",\"status\":\"DONE\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(get(BASE).param("status", "DONE"))
                .andExpect(jsonPath("$[0].title").value("done import"));
    }

    @Test
    void importRoundTripsAnExportFile() throws Exception {
        long id = createTask("round trip");
        mockMvc.perform(put(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"round trip\",\"priority\":\"URGENT\",\"tags\":[\"x\"]}"))
                .andExpect(status().isOk());

        String exported = mockMvc.perform(get(BASE + "/export"))
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode tasks =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(exported).get("tasks");

        // The exported rows are sent straight back, extra read-only fields and all.
        String body = "{\"mode\":\"merge\",\"tasks\":" + tasks + "}";
        mockMvc.perform(post(BASE + "/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void importReplaceClearsTasksAndHistoryAtomically() throws Exception {
        long old = createTask("old");
        moveTo(old, "DOING");

        mockMvc.perform(post(BASE + "/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"replace\",\"confirm\":\"replace\","
                                + "\"tasks\":[{\"title\":\"fresh\",\"status\":\"TODO\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get(BASE))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("fresh"));

        // The previous board's history went with it: the new row id is not the old task.
        mockMvc.perform(get(BASE + "/" + old + "/activities"))
                .andExpect(status().isNotFound());
    }

    @Test
    void importReplaceRequiresConfirmTokenAndLeavesDataIntact() throws Exception {
        createTask("survivor");

        mockMvc.perform(post(BASE + "/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"replace\",\"tasks\":[{\"title\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPLACE_NOT_CONFIRMED"));

        mockMvc.perform(post(BASE + "/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"replace\",\"confirm\":\"yes\",\"tasks\":[{\"title\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPLACE_NOT_CONFIRMED"));

        mockMvc.perform(get(BASE))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("survivor"));
    }

    @Test
    void importRejectsBadRequestShapes() throws Exception {
        String[] badBodies = {
                "{}",
                "{\"mode\":\"append\",\"tasks\":[]}",
                "{\"mode\":\"merge\"}",
                "{\"mode\":\"merge\",\"tasks\":\"nope\"}",
                "{\"mode\":\"merge\",\"tasks\":{}}"
        };
        for (String body : badBodies) {
            mockMvc.perform(post(BASE + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void importSkipsRowsWithWronglyTypedValuesInsteadOfSilentlyDefaulting() throws Exception {
        // A number where a status belongs must not be read as "status omitted" and imported as
        // TODO. Same for a string where the tag array belongs. Both are row-level skips.
        String body = "{\"mode\":\"merge\",\"tasks\":["
                + "{\"title\":\"numeric status\",\"status\":2},"
                + "{\"title\":\"numeric priority\",\"priority\":1},"
                + "{\"title\":\"string tags\",\"tags\":\"a\"},"
                + "{\"title\":\"non-text tag\",\"tags\":[\"ok\",7]},"
                + "{\"title\":\"numeric title\",\"title\":5},"
                + "{\"title\":\"only survivor\"}"
                + "]}";

        mockMvc.perform(post(BASE + "/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(5))
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get(BASE))
                .andExpect(jsonPath("$[0].title").value("only survivor"))
                .andExpect(jsonPath("$[0].status").value("TODO"));
    }

    @Test
    void importRejectsMoreThanOneThousandRows() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("{\"title\":\"t").append(i).append("\"}");
        }
        mockMvc.perform(post(BASE + "/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"merge\",\"tasks\":[" + rows + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /* ------------------------------------------------------------- persistence */

    @Test
    void entitiesRoundTripThroughTheDatabaseIncludingOrderedTags() throws Exception {
        long id = createTask("persisted");
        mockMvc.perform(put(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"persisted\",\"tags\":[\"z\",\"a\",\"m\"]}"))
                .andExpect(status().isOk());

        // Read straight from the row store, not through the service's mapping, so the tag order
        // column and the enum-as-string storage are proven on disk rather than in a cache.
        List<String> tagColumn = entityManager
                .createNativeQuery("select tag from task_tags where task_id = ?1 order by tag_order", String.class)
                .setParameter(1, id)
                .getResultList();
        assertEquals(List.of("z", "a", "m"), tagColumn);

        String statusColumn = (String) entityManager
                .createNativeQuery("select status from tasks where id = ?1")
                .setParameter(1, id)
                .getSingleResult();
        assertEquals("TODO", statusColumn, "enums must be stored by name, not ordinal");

        assertTrue(taskRepository.findById(id).isPresent());
        assertEquals(TaskActivityType.CREATED,
                activityRepository.findAll().stream()
                        .filter(row -> row.getTaskId() == id)
                        .map(row -> row.getType())
                        .findFirst().orElseThrow());
    }

    @Test
    void domainMappingKeepsEveryField() throws Exception {
        long id = createTask("map me");
        mockMvc.perform(put(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"map me\",\"priority\":\"URGENT\",\"assignee\":\"alice\","
                                + "\"dueDate\":\"2026-06-30\",\"tags\":[\"one\"]}"))
                .andExpect(status().isOk());

        Task task = taskService.findById(id);
        assertEquals("map me", task.title());
        assertEquals(TaskPriority.URGENT, task.priority());
        assertEquals(TaskStatus.TODO, task.status());
        assertEquals("alice", task.assignee());
        assertEquals(java.time.LocalDate.parse("2026-06-30"), task.dueDate());
        assertEquals(List.of("one"), task.tags());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), task.createdAt());
    }

    @Test
    void updateActuallyPersistsFieldChanges() throws Exception {
        long id = createTask("v1");
        mockMvc.perform(put(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"v2\",\"tags\":[\"new\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"v3\",\"tags\":[]}"))
                .andExpect(status().isOk());

        // Clearing tags must remove the child rows, not leave stale ones behind.
        assertEquals(0, ((Number) entityManager
                .createNativeQuery("select count(*) from task_tags where task_id = ?1")
                .setParameter(1, id).getSingleResult()).intValue());
        mockMvc.perform(get(BASE + "/" + id)).andExpect(jsonPath("$.title").value("v3"));
    }
}