package com.example.taskboard;

import com.example.taskboard.model.Task;
import com.example.taskboard.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskImportExportApiTest {

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
    void mergeAppendsRowsAndRespectsTheirStatus() throws Exception {
        String body = """
                {"mode":"merge","tasks":[
                  {"title":"restored done","status":"DONE","priority":"URGENT","tags":["a"]},
                  {"title":"restored doing","status":"DOING","priority":"HIGH"}
                ]}
                """;

        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("merge"))
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.total").value(2));

        // Import is the restore path: DONE must survive, even though TODO->DONE is forbidden
        // for interactive clients. Losing it would silently un-finish restored work.
        assertEquals(1, taskService.findAll(new com.example.taskboard.service.TaskQuery(
                List.of(com.example.taskboard.model.TaskStatus.DONE), null, null, null, null,
                null, true, null)).size());
    }

    @Test
    void invalidRowsAreSkippedOneByOneAndValidRowsStillImport() throws Exception {
        String body = """
                {"mode":"merge","tasks":[
                  {"title":"good"},
                  {"title":"   "},
                  {"title":"bad status","status":"NOPE"},
                  {"title":"bad priority","priority":"NOPE"},
                  {"title":"bad date","dueDate":"2026-13-45"},
                  {"title":"good too","priority":"LOW"}
                ]}
                """;

        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").value(4))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void tagsAreNormalizedBeforeTheFiveTagLimitIsChecked() throws Exception {
        // Six raw entries that normalise to three legal tags: the row must be accepted.
        String accepted = """
                {"mode":"merge","tasks":[
                  {"title":"normalised","tags":["  a  ","b","a","","  ","b"]}
                ]}
                """;
        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accepted))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(0));

        // Six distinct tags stay six after normalisation: the row is skipped.
        String rejected = """
                {"mode":"merge","tasks":[
                  {"title":"too many","tags":["a","b","c","d","e","f"]}
                ]}
                """;
        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejected))
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.skipped").value(1));

        List<Task> all = taskService.findAll(null);
        Task normalised = all.stream().filter(task -> task.title().equals("normalised")).findFirst().orElseThrow();
        assertEquals(List.of("a", "b"), normalised.tags());
    }

    @Test
    void exportedFileCanBeImportedBackWithItsStatusesIntact() throws Exception {
        long id = createTask("round trip");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/tasks/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DOING\"}"))
                .andExpect(status().isOk());

        String export = mockMvc.perform(get("/api/tasks/export"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode file = objectMapper.readTree(export);

        List<Task> before = taskService.findAll(null);

        // Feed the file back verbatim: every read-only field (id, createdAt, overdue...) must be
        // ignored, and the status must survive. This is the exact "export then restore" path.
        String importBody = objectMapper.writeValueAsString(
                java.util.Map.of("mode", "replace", "confirm", "replace", "tasks", file.get("tasks")));
        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(before.size()))
                .andExpect(jsonPath("$.total").value(before.size()));

        Task restored = taskService.findAll(null).stream()
                .filter(task -> task.title().equals("round trip")).findFirst().orElseThrow();
        assertEquals(com.example.taskboard.model.TaskStatus.DOING, restored.status());
    }

    @Test
    void replaceClearsEverythingIncludingActivities() throws Exception {
        long old = createTask("old task");
        taskService.updateStatus(old, com.example.taskboard.model.TaskStatus.DOING);

        String body = """
                {"mode":"replace","confirm":"replace","tasks":[{"title":"only one","status":"TODO"}]}
                """;
        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("only one"));
        // Old activities must not survive a replace: the ids they point at now mean other tasks.
        mockMvc.perform(get("/api/tasks/" + old + "/activities"))
                .andExpect(status().isNotFound());
    }

    @Test
    void replaceWithoutConfirmIs400ReplaceNotConfirmed() throws Exception {
        createTask("survivor");

        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"replace\",\"tasks\":[{\"title\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPLACE_NOT_CONFIRMED"));

        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"replace\",\"confirm\":\"yes\",\"tasks\":[{\"title\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPLACE_NOT_CONFIRMED"));

        // A rejected replace must leave the board untouched.
        mockMvc.perform(get("/api/tasks")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void missingOrNonArrayTasksIs400ValidationFailed() throws Exception {
        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"merge\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"merge\",\"tasks\":\"oops\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownModeIs400ValidationFailed() throws Exception {
        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"upsert\",\"tasks\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tasks\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void moreThan1000RowsIs400() throws Exception {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            rows.add("{\"title\":\"row " + i + "\"}");
        }
        String body = "{\"mode\":\"merge\",\"tasks\":[" + String.join(",", rows) + "]}";

        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertEquals(0L, taskService.findAll(null).size());
    }

    @Test
    void exactly1000RowsIsAccepted() throws Exception {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add("{\"title\":\"row " + i + "\"}");
        }
        String body = "{\"mode\":\"merge\",\"tasks\":[" + String.join(",", rows) + "]}";

        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1000));
    }

    @Test
    void importedIdsAreFreshAndNotTakenFromTheFile() throws Exception {
        String body = """
                {"mode":"merge","tasks":[
                  {"id":999,"title":"claims id 999","createdAt":"2000-01-01T00:00:00Z","updatedAt":"2000-01-01T00:00:00Z"}
                ]}
                """;
        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Task imported = taskService.findAll(null).get(0);
        // The file's id is ignored: reusing it would either collide on merge or leave the id
        // sequence behind the stored ids.
        org.junit.jupiter.api.Assertions.assertNotEquals(999L, imported.id());
        org.junit.jupiter.api.Assertions.assertTrue(imported.createdAt().isAfter(java.time.Instant.parse("2020-01-01T00:00:00Z")));
    }

    @Test
    void importRowWithWrongJsonTypeIsSkippedNotFatal() throws Exception {
        String body = """
                {"mode":"merge","tasks":[
                  {"title":123},
                  {"title":"fine"}
                ]}
                """;

        mockMvc.perform(post("/api/tasks/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(1));
    }

    private long createTask(String title) throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("title", title))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }
}