package com.filesummer.gradle;

import com.filesummer.model.ProjectConfig;
import com.filesummer.model.RuleGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleScriptGeneratorTest {

    private ProjectConfig sample() {
        ProjectConfig p = new ProjectConfig();
        p.setName("文档收集");
        p.setOutputDir("D:\\out with space");
        p.setRecursive(true);
        RuleGroup g1 = new RuleGroup();
        g1.setName("文档");
        g1.setSourceDirs(List.of("D:\\docs", "D:\\more docs"));
        g1.setSubDir("01_DD");
        g1.setIncludeSuffixes(List.of("docx", "pdf"));
        g1.setExcludeSuffixes(List.of("tmp"));
        g1.setExcludeDirFragments(List.of("node_modules", "Temp Files"));
        p.getRules().add(g1);
        RuleGroup g2 = new RuleGroup();
        g2.setName("src");
        g2.setSourceDirs(List.of("D:\\workspace\\src"));
        g2.setSubDir("02_source");
        g2.setIncludeSuffixes(List.of("java"));
        p.getRules().add(g2);
        RuleGroup off = new RuleGroup();
        off.setName("stopped");
        off.setEnabled(false);
        off.setSourceDirs(List.of("D:\\never"));
        p.getRules().add(off);
        return p;
    }

    @Test
    void scriptCoversEveryEnabledGroupWithCorrectSemantics() {
        String script = new GradleScriptGenerator().buildScript(sample());
        assertTrue(script.contains("tasks.register('collectAll')"));
        assertTrue(script.contains("collect_1_group"));       // 文档 -> ascii fallback 'group'? no: sanitize drops CJK
        assertTrue(script.contains("collect_2_src"));
        assertTrue(script.contains("file('D:\\\\out with space')"));
        assertTrue(script.contains("from('D:\\\\docs')"));
        assertTrue(script.contains("include '**/*.docx'"));
        assertTrue(script.contains("exclude '**/*.tmp'"));
        // directory fragments must use the closure form (relative path), never Ant pattern
        assertTrue(script.contains("exclude { d ->"));
        assertTrue(script.contains("d.relativePath.toString().toLowerCase().contains(f)"));
        assertTrue(script.contains("'node_modules'"));
        assertTrue(script.contains("'temp files'"));
        assertTrue(script.contains("new File(outputRoot, '01_DD')"));
        assertTrue(script.contains("_stamps[f.relativePath.pathString] = f.file.lastModified()"));
        assertTrue(script.contains("import java.nio.file.Files"));
        assertTrue(script.contains("Files.setLastModifiedTime(destFile.toPath(), FileTime.fromMillis(t))"));
        // disabled group absent
        assertTrue(!script.contains("D:\\never"));
        assertTrue(script.contains("dependsOn('collect_1_group')"));
    }

    @Test
    void dateModesBecomeLastModifiedExclusions() {
        ProjectConfig p = sample();
        RuleGroup g = p.getRules().get(0);
        long from = com.filesummer.core.FileFilters.startOfDayMillis(java.time.LocalDate.parse("2024-05-01"));
        long toNext = com.filesummer.core.FileFilters.startOfDayMillis(java.time.LocalDate.parse("2024-05-02"));

        g.setDateMode("AFTER");
        g.setDateFrom("2024-05-01");
        String script = new GradleScriptGenerator().buildScript(p);
        assertTrue(script.contains(
                "exclude { d -> !d.file.isDirectory() && (d.file.lastModified() < " + from + "L) }"));

        g.setDateMode("BEFORE");
        g.setDateFrom(null);
        g.setDateTo("2024-05-01");
        script = new GradleScriptGenerator().buildScript(p);
        assertTrue(script.contains(
                "exclude { d -> !d.file.isDirectory() && (d.file.lastModified() >= " + from + "L) }"));

        g.setDateMode("RANGE");
        g.setDateFrom("2024-05-01");
        g.setDateTo("2024-05-01");
        script = new GradleScriptGenerator().buildScript(p);
        assertTrue(script.contains("exclude { d -> !d.file.isDirectory() && (d.file.lastModified() < "
                + from + "L || d.file.lastModified() >= " + toNext + "L) }"));
        // only the dated group gets the exclusion, once per from-block (sample has 2 source dirs)
        assertEquals(2, script.lines().filter(l -> l.contains("lastModified() <")).count());

        g.setDateMode("RANGE");
        g.setDateTo(null);   // inert filter -> no exclusion lines at all
        script = new GradleScriptGenerator().buildScript(p);
        assertEquals(0, script.lines().filter(l -> l.contains("exclude { d -> !d.file.isDirectory()")).count());
    }

    @Test
    void nonRecursiveUsesFlatIncludePattern() {
        ProjectConfig p = sample();
        p.getRules().forEach(g -> g.setRecursive(false));
        String script = new GradleScriptGenerator().buildScript(p);
        assertTrue(script.contains("include '*.docx'"));
        assertTrue(!script.contains("include '**/*.docx'"));
    }

    @Test
    void generateWritesFullRunnableProject(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("collector");
        new GradleScriptGenerator().generate(sample(), target);
        assertTrue(Files.exists(target.resolve("build.gradle")));
        assertTrue(Files.exists(target.resolve("collect.gradle")));
        assertTrue(Files.exists(target.resolve("settings.gradle")));
        assertTrue(Files.exists(target.resolve("gradlew.bat")));
        assertTrue(Files.exists(target.resolve("gradle/wrapper/gradle-wrapper.jar")));
        assertTrue(Files.exists(target.resolve("gradle/wrapper/gradle-wrapper.properties")));
        String readme = Files.readString(target.resolve("README.txt"));
        assertTrue(readme.contains("gradlew.bat collectAll"));
        assertEquals(Files.readString(target.resolve("build.gradle")),
                Files.readString(target.resolve("collect.gradle")));
    }

    @Test
    void scriptFilterBecomesKeepMethodAndExclusion() {
        ProjectConfig p = sample();
        RuleGroup g = p.getRules().get(0);
        g.setFilterScript("f.length() > 1024 && !relPath.contains('draft')");
        String script = new GradleScriptGenerator().buildScript(p);
        assertTrue(script.contains(
                "def collect_1_group_keep(File f, String relPath, String name) {"));
        assertTrue(script.contains("    f.length() > 1024 && !relPath.contains('draft')"));
        assertTrue(script.contains("exclude { d -> d.file.isFile() && !"
                + "collect_1_group_keep(d.file, d.relativePath.pathString, d.file.name) }"));
        // groups without a script emit no keep method/exclusion
        assertTrue(!script.contains("collect_2_src_keep"));
        assertEquals(2, script.lines().filter(l -> l.contains("d.file.isFile() && !collect_")).count());

        g.setFilterScript("   ");   // blank -> inert
        script = new GradleScriptGenerator().buildScript(p);
        assertTrue(!script.contains("_keep("));
    }

    @Test
    void escapingKeepsBackslashesAndQuotes() {
        assertEquals("C:\\\\path\\'s", GradleScriptGenerator.groovyEscape("C:\\path's"));
        assertEquals("group", GradleScriptGenerator.sanitizeName("  "));
    }
}
