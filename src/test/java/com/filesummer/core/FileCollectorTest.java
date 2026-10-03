package com.filesummer.core;

import com.filesummer.model.ConflictPolicy;
import com.filesummer.model.ProjectConfig;
import com.filesummer.model.RuleGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCollectorTest {

    @TempDir
    Path tmp;

    Path srcRoot;
    Path outDir;
    ProjectConfig project;
    RuleGroup group;

    @BeforeEach
    void setUp() throws IOException {
        srcRoot = tmp.resolve("subroot");   // absolute path contains "sub"
        outDir = tmp.resolve("out");
        Files.createDirectories(srcRoot.resolve("node_modules"));
        Files.createDirectories(srcRoot.resolve("build"));
        Files.createDirectories(srcRoot.resolve("deep"));
        Files.createDirectories(srcRoot.resolve("deep/sub dir"));
        write(srcRoot.resolve("a.java"));
        write(srcRoot.resolve("notes.txt"));
        write(srcRoot.resolve("node_modules/lib.java"));
        write(srcRoot.resolve("build/generated.java"));
        write(srcRoot.resolve("a.log.tmp"));
        write(srcRoot.resolve("deep/sub dir/inner.java"));
        write(srcRoot.resolve("node_modules.java"));

        group = new RuleGroup();
        group.setName("代码");
        group.setSourceDirs(List.of(srcRoot.toString()));
        group.setSubDir("01_code");

        project = new ProjectConfig();
        project.setName("测试项目");
        project.setOutputDir(outDir.toString());
        group.setRecursive(true);
        project.getRules().add(group);
    }

    private void write(Path p) throws IOException {
        Files.writeString(p, "content of " + p.getFileName());
    }

    @Test
    void includeSuffixesFilter() {
        group.setIncludeSuffixes(List.of("java"));
        CollectReport r = new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        // a.java, node_modules/lib.java, build/generated.java, deep/sub dir/inner.java, node_modules.java
        assertEquals(5, r.totalMatched());
        assertTrue(Files.exists(outDir.resolve("01_code/a.java")));
    }

    @Test
    void excludeSuffixWinsOverInclude() {
        group.setIncludeSuffixes(List.of("java", "tmp"));
        group.setExcludeSuffixes(List.of("TMP"));
        new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertFalse(Files.exists(outDir.resolve("01_code/a.log.tmp")));
        assertTrue(Files.exists(outDir.resolve("01_code/a.java")));
    }

    @Test
    void dirFragmentMatchesRelativePathCaseInsensitively() {
        group.setIncludeSuffixes(List.of("java"));
        group.setExcludeDirFragments(List.of("NODE_modules"));
        new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertFalse(Files.exists(outDir.resolve("01_code/node_modules/lib.java")));
        assertTrue(Files.exists(outDir.resolve("01_code/build/generated.java")));
        // relative-path semantics: 'sub' appears in the ABSOLUTE root path but must not match
        assertTrue(Files.exists(outDir.resolve("01_code/deep/sub dir/inner.java")));
    }

    @Test
    void dirFragmentMatchesWholeRelativePathIncludingFileName() {
        group.setIncludeSuffixes(List.of("java"));
        group.setExcludeDirFragments(List.of("modules.java"));
        new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        // rel path 'node_modules.java' contains the fragment -> excluded
        assertFalse(Files.exists(outDir.resolve("01_code/node_modules.java")));
    }

    @Test
    void nonRecursiveScansOnlyTopLevel() {
        group.setRecursive(false);
        group.setIncludeSuffixes(List.of("java"));
        CollectReport r = new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertEquals(2, r.totalMatched()); // a.java + node_modules.java
        assertTrue(Files.exists(outDir.resolve("01_code/a.java")));
        assertFalse(Files.exists(outDir.resolve("01_code/deep/sub dir/inner.java")));
    }

    @Test
    void copyPreservesLastModified() throws IOException {
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 86400000L * 30);
        Files.setLastModifiedTime(srcRoot.resolve("a.java"), old);
        group.setIncludeSuffixes(List.of("java"));
        new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertEquals(old.toMillis(),
                Files.getLastModifiedTime(outDir.resolve("01_code/a.java")).toMillis());
    }

    @Test
    void zipPreservesLastModified() throws IOException {
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 86400000L * 10);
        Files.setLastModifiedTime(srcRoot.resolve("a.java"), old);
        group.setIncludeSuffixes(List.of("java"));
        CollectReport r = new FileCollector().collect(project, FileCollector.OutputMode.ZIP);
        Path zip = Path.of(r.getOutputLocation());
        assertTrue(Files.exists(zip));
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entry = zf.getEntry("01_code/a.java");
            // ZIP stores DOS timestamps with 2-second resolution
            assertTrue(Math.abs(old.toMillis() - entry.getLastModifiedTime().toMillis()) < 2000);
        }
    }

    @Test
    void missingDirAndNoMatchAreReported() throws IOException {
        group.setSourceDirs(List.of(tmp.resolve("ghost").toString()));
        CollectReport r = new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertTrue(r.hasErrors());
        assertTrue(r.getIssues().stream().anyMatch(i -> i.getMessage().contains("目录不存在")));

        group.setSourceDirs(List.of(srcRoot.toString()));
        group.setIncludeSuffixes(List.of("xyz"));
        CollectReport r2 = new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertEquals(0, r2.totalMatched());
        assertTrue(r2.getIssues().stream().anyMatch(i -> i.getMessage().contains("没有匹配到任何文件")));
    }

    @Test
    void conflictPolicySkipKeepsExistingFile() throws IOException {
        Path dest = outDir.resolve("01_code/a.java");
        Files.createDirectories(dest.getParent());
        Files.writeString(dest, "existing", StandardCharsets.UTF_8);
        project.setConflictPolicy(ConflictPolicy.SKIP);
        group.setIncludeSuffixes(List.of("java"));
        CollectReport r = new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertEquals("existing", Files.readString(dest));
        CollectReport.GroupStat st = r.getStats().get(0);
        assertTrue(st.skippedByConflict >= 1);
    }

    @Test
    void dateFilterKeepsOnlyFilesModifiedOnOrAfter() throws IOException {
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 86400000L * 365);
        Files.setLastModifiedTime(srcRoot.resolve("a.java"), old);
        group.setIncludeSuffixes(List.of("java"));
        group.setDateMode("AFTER");
        group.setDateFrom(java.time.LocalDate.now().minusDays(7).toString());
        CollectReport r = new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertFalse(Files.exists(outDir.resolve("01_code/a.java")));
        assertTrue(Files.exists(outDir.resolve("01_code/deep/sub dir/inner.java")));
        assertEquals(4, r.totalMatched());
    }

    @Test
    void dateBeforeFilterKeepsOnlyOlderFiles() throws IOException {
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 86400000L * 365);
        Files.setLastModifiedTime(srcRoot.resolve("a.java"), old);
        group.setIncludeSuffixes(List.of("java"));
        group.setDateMode("BEFORE");
        group.setDateTo(java.time.LocalDate.now().minusDays(30).toString());
        CollectReport r = new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertEquals(1, r.totalMatched());
        assertTrue(Files.exists(outDir.resolve("01_code/a.java")));
    }

    @Test
    void dateRangeFilterIsInclusiveBothEnds() throws IOException {
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 86400000L * 365);
        Files.setLastModifiedTime(srcRoot.resolve("a.java"), old);
        group.setIncludeSuffixes(List.of("java"));
        group.setDateMode("RANGE");
        group.setDateFrom(java.time.LocalDate.now().minusDays(7).toString());
        group.setDateTo(java.time.LocalDate.now().toString());
        CollectReport r = new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertEquals(4, r.totalMatched());
        // upper bound day itself is included: files modified now match
        assertTrue(Files.exists(outDir.resolve("01_code/build/generated.java")));
    }

    @Test
    void invalidOrMissingDateFilterIsIgnored() {
        group.setIncludeSuffixes(List.of("java"));
        group.setDateMode("AFTER");
        group.setDateFrom("not-a-date");
        assertEquals(5, new FileCollector().collect(project, FileCollector.OutputMode.COPY).totalMatched());

        group.setDateMode("RANGE");
        group.setDateFrom("2024-01-01");
        group.setDateTo(null);   // RANGE without both ends -> inert, never filters everything out
        assertEquals(5, new FileCollector().collect(project, FileCollector.OutputMode.COPY).totalMatched());
    }

    @Test
    void scriptFilterKeepsByExpression() {
        group.setIncludeSuffixes(List.of("java"));
        group.setFilterScript("relPath.startsWith('deep/')");
        CollectReport r = new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertEquals(1, r.totalMatched());
        assertTrue(Files.exists(outDir.resolve("01_code/deep/sub dir/inner.java")));
        assertFalse(Files.exists(outDir.resolve("01_code/a.java")));
    }

    @Test
    void scriptFilterSupportsFileApiAndExplicitReturn() throws IOException {
        Files.writeString(srcRoot.resolve("big.java"), "x".repeat(500));
        group.setIncludeSuffixes(List.of("java"));
        group.setFilterScript("if (f.length() > 100) return false\nreturn name != 'node_modules.java'");
        CollectReport r = new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        // big.java dropped by size, node_modules.java by name; a/lib/generated/inner kept
        assertEquals(4, r.totalMatched());
        assertFalse(Files.exists(outDir.resolve("01_code/big.java")));
        assertFalse(Files.exists(outDir.resolve("01_code/node_modules.java")));
        assertTrue(Files.exists(outDir.resolve("01_code/a.java")));
    }

    @Test
    void scriptCompileErrorAbortsRun() {
        group.setFilterScript("def broken = (");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new FileCollector().collect(project, FileCollector.OutputMode.COPY));
        assertTrue(ex.getMessage().contains("脚本编译失败"));
    }

    @Test
    void scriptRuntimeErrorAbortsRun() {
        group.setIncludeSuffixes(List.of("java"));
        group.setFilterScript("Integer.valueOf('bad-number') > 0");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new FileCollector().collect(project, FileCollector.OutputMode.COPY));
        assertTrue(ex.getMessage().contains("执行 Groovy 脚本失败"));
    }

    @Test
    void disabledGroupIsSkipped() throws IOException {
        group.setEnabled(false);
        RuleGroup other = new RuleGroup();
        other.setName("停用组");
        other.setEnabled(false);
        other.setSourceDirs(List.of(srcRoot.toString()));
        project.getRules().add(other);
        CollectReport r = new FileCollector().collect(project, FileCollector.OutputMode.COPY);
        assertEquals(0, r.totalMatched());
    }
}
