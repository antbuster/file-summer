package com.filesummer.config;

import com.filesummer.model.ConflictPolicy;
import com.filesummer.model.ProjectConfig;
import com.filesummer.model.RuleGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectStoreTest {

    @TempDir
    Path dir;

    @Test
    void missingFileYieldsDefaults() {
        ProjectStore store = new ProjectStore(dir);
        ProjectStore.LoadResult r = store.load();
        assertEquals(ProjectStore.LoadStatus.CREATED_DEFAULTS, r.status());
        assertEquals(1, r.workspace().getProjects().size());
        assertTrue(r.message().contains("默认"));
    }

    @Test
    void corruptFileFallsBackToDefaults() throws IOException {
        Files.writeString(dir.resolve("projects.json"), "{ this is not json ");
        ProjectStore store = new ProjectStore(dir);
        ProjectStore.LoadResult r = store.load();
        assertEquals(ProjectStore.LoadStatus.CORRUPTED_RESET_TO_DEFAULTS, r.status());
        assertNotNull(r.workspace().getCurrentProject());
        store.quarantineCorruptFile();
        assertTrue(Files.exists(dir.resolve("projects.json.corrupt")));
    }

    @Test
    void corruptFileRestoresFromBackup() throws IOException {
        ProjectStore store = new ProjectStore(dir);
        Workspace w = Workspace.withDefaults();
        w.getCurrentProject().setName("备份项目");
        store.save(w);                       // writes projects.json
        store.save(w);                       // previous file becomes .bak
        Files.writeString(dir.resolve("projects.json"), "!!!!broken");
        ProjectStore.LoadResult r = store.load();
        assertEquals(ProjectStore.LoadStatus.RESTORED_FROM_BACKUP, r.status());
        assertEquals("备份项目", r.workspace().getProjects().get(0).getName());
    }

    @Test
    void partialConfigGetsSanitized() throws IOException {
        String json = """
                { "schemaVersion": 1, "projects": [ { "name": "半残项目", "rules": [ { } ] } ],
                  "currentProjectIndex": 5 }
                """;
        Files.writeString(dir.resolve("projects.json"), json);
        ProjectStore store = new ProjectStore(dir);
        ProjectStore.LoadResult r = store.load();
        assertEquals(ProjectStore.LoadStatus.OK, r.status());
        ProjectConfig p = r.workspace().getCurrentProject();
        assertEquals("半残项目", p.getName());
        assertEquals("", p.getOutputDir());
        assertEquals(ConflictPolicy.OVERWRITE, p.getConflictPolicy());
        assertTrue(p.isRecursive());          // missing field defaults to recursive
        assertEquals(1, p.getRules().size());
        RuleGroup g = p.getRules().get(0);
        assertEquals("任务", g.getName());
        assertNotNull(g.getSourceDirs());
        assertNull(g.getDateMode());        // new field absent in old configs -> no date limit
    }

    @Test
    void schemaV1ConfigLoadsAndRoundTrips() throws IOException {
        String v1 = """
                {
                  "schemaVersion" : 1,
                  "projects" : [ {
                    "name" : "默认项目",
                    "outputDir" : "D:\\\\out",
                    "conflictPolicy" : "OVERWRITE",
                    "rules" : [ {
                      "name" : "任务1",
                      "enabled" : true,
                      "sourceDirs" : [ "D:\\\\src" ],
                      "subDir" : "01",
                      "legacyOutputDir" : "",
                      "includeSuffixes" : [ "java" ],
                      "excludeSuffixes" : [ ],
                      "excludeDirFragments" : [ ]
                    } ]
                  } ],
                  "currentProjectIndex" : 0
                }
                """;
        Files.writeString(dir.resolve("projects.json"), v1);
        ProjectStore store = new ProjectStore(dir);
        ProjectStore.LoadResult r = store.load();
        assertEquals(ProjectStore.LoadStatus.OK, r.status());
        ProjectConfig p = r.workspace().getCurrentProject();
        assertEquals(List.of("D:\\src"), p.getRules().get(0).getSourceDirs());
        assertTrue(p.isRecursive());

        store.save(r.workspace());
        ProjectStore.LoadResult again = new ProjectStore(dir).load();
        assertEquals(ProjectStore.LoadStatus.OK, again.status());
        assertEquals("01", again.workspace().getCurrentProject().getRules().get(0).getSubDir());
    }

    @Test
    void legacyModifiedSinceMigratesToAfterMode() throws IOException {
        String json = """
                { "schemaVersion": 1, "projects": [ { "name": "旧配置", "rules": [ {
                    "name": "任务1", "modifiedSince": "2024-05-01" } ] } ], "currentProjectIndex": 0 }
                """;
        Files.writeString(dir.resolve("projects.json"), json);
        ProjectStore.LoadResult r = new ProjectStore(dir).load();
        RuleGroup g = r.workspace().getCurrentProject().getRules().get(0);
        assertEquals("AFTER", g.getDateMode());
        assertEquals("2024-05-01", g.getDateFrom());
        assertNull(g.getDateTo());
    }

    @Test
    void legacyProjectRecursiveFalsePushesDownToRules() throws IOException {
        String json = """
                { "schemaVersion": 1, "projects": [ { "name": "旧配置", "recursive": false,
                    "rules": [ { "name": "任务1" }, { "name": "任务2" } ] } ], "currentProjectIndex": 0 }
                """;
        Files.writeString(dir.resolve("projects.json"), json);
        ProjectConfig p = new ProjectStore(dir).load().workspace().getCurrentProject();
        assertTrue(p.isRecursive());                 // project flag normalized
        assertTrue(p.getRules().stream().noneMatch(RuleGroup::isRecursive));
    }

    @Test
    void dateModesRoundTrip() throws IOException {
        ProjectStore store = new ProjectStore(dir);
        Workspace w = Workspace.withDefaults();
        RuleGroup g = w.getCurrentProject().getRules().get(0);
        g.setDateMode("RANGE");
        g.setDateFrom("2024-01-05");
        g.setDateTo("2024-06-20");
        store.save(w);
        RuleGroup reloaded = new ProjectStore(dir).load().workspace().getCurrentProject().getRules().get(0);
        assertEquals("RANGE", reloaded.getDateMode());
        assertEquals("2024-01-05", reloaded.getDateFrom());
        assertEquals("2024-06-20", reloaded.getDateTo());
    }

    @Test
    void filterScriptRoundTrips() throws IOException {
        ProjectStore store = new ProjectStore(dir);
        Workspace w = Workspace.withDefaults();
        RuleGroup g = w.getCurrentProject().getRules().get(0);
        g.setFilterScript("f.length() > 1024");
        store.save(w);
        RuleGroup reloaded = new ProjectStore(dir).load().workspace().getCurrentProject().getRules().get(0);
        assertEquals("f.length() > 1024", reloaded.getFilterScript());
    }

    @Test
    void groupRecursiveRoundTrips() throws IOException {
        ProjectStore store = new ProjectStore(dir);
        Workspace w = Workspace.withDefaults();
        w.getCurrentProject().getRules().get(0).setRecursive(false);
        store.save(w);
        RuleGroup reloaded = new ProjectStore(dir).load().workspace().getCurrentProject().getRules().get(0);
        assertFalse(reloaded.isRecursive());
    }

    @Test
    void multipleProjectsRestoreCurrentIndex() throws IOException {
        ProjectStore store = new ProjectStore(dir);
        Workspace w = Workspace.withDefaults();
        ProjectConfig p2 = new ProjectConfig();
        p2.setName("项目二");
        p2.setOutputDir("D:/x");
        p2.getRules().add(p2.newDefaultGroup());
        w.getProjects().add(p2);
        w.setCurrentProjectIndex(1);
        store.save(w);

        ProjectStore.LoadResult r = new ProjectStore(dir).load();
        assertEquals(2, r.workspace().getProjects().size());
        assertEquals("项目二", r.workspace().getCurrentProject().getName());
    }
}
