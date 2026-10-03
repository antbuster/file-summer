package com.filesummer.core;

import com.filesummer.i18n.I18n;
import com.filesummer.model.ProjectConfig;
import com.filesummer.model.RuleGroup;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * In-app collector: scans configured directories, applies the group filters
 * and writes results (copy or ZIP) while preserving original last-modified times.
 */
public class FileCollector {

    public enum OutputMode { COPY, ZIP }

    private record Candidate(RuleGroup group, Path root, Path file) {
    }

    public CollectReport collect(ProjectConfig project, OutputMode mode) {
        CollectReport report = new CollectReport();
        if (project.getOutputDir() == null || project.getOutputDir().isBlank()) {
            report.error(null, null, I18n.t("collect.err.no_output"));
            return report;
        }
        Path outputDir = Paths.get(project.getOutputDir());
        List<Candidate> candidates = new ArrayList<>();
        for (RuleGroup group : project.getRules()) {
            if (!group.isEnabled()) {
                continue;
            }
            CollectReport.GroupStat stat = report.statFor(group.getName());
            if (group.getSourceDirs().isEmpty()) {
                report.warn(group.getName(), null, I18n.t("collect.warn.no_source_dirs"));
                continue;
            }
            GroovyFilter scriptFilter = compileScriptFilter(group);
            for (String dir : group.getSourceDirs()) {
                Path root = Paths.get(dir);
                if (!Files.exists(root)) {
                    report.error(group.getName(), dir, I18n.t("collect.err.dir_missing"));
                    continue;
                }
                if (!Files.isDirectory(root)) {
                    report.error(group.getName(), dir, I18n.t("collect.err.not_dir"));
                    continue;
                }
                scan(root, group, group.isRecursive(), scriptFilter, candidates, report);
            }
            if (stat.matched == 0) {
                report.warn(group.getName(), null, I18n.t("collect.warn.no_match"));
            }
        }
        try {
            Files.createDirectories(outputDir);
        } catch (IOException | RuntimeException e) {
            report.error(null, outputDir.toString(), I18n.t("collect.err.create_out", e.getMessage()));
            return report;
        }
        if (mode == OutputMode.ZIP) {
            writeZip(project, outputDir, candidates, report);
        } else {
            writeCopy(project, outputDir, candidates, report);
        }
        return report;
    }

    /** Script compile errors abort the whole run (surfaced by the UI as a collection failure). */
    private GroovyFilter compileScriptFilter(RuleGroup group) {
        String script = group.getFilterScript();
        if (script == null || script.isBlank()) {
            return null;
        }
        try {
            return GroovyFilter.compile(script);
        } catch (Exception e) {
            throw new IllegalStateException(I18n.t("collect.err.script_compile",
                    group.getName(), e.getMessage()), e);
        }
    }

    private void scan(Path root, RuleGroup group, boolean recursive, GroovyFilter scriptFilter,
                      List<Candidate> candidates, CollectReport report) {
        CollectReport.GroupStat stat = report.statFor(group.getName());
        List<Path> queue = new ArrayList<>();
        queue.add(root);
        int head = 0;
        while (head < queue.size()) {
            Path dir = queue.get(head++);
            List<Path> entries;
            try (var stream = Files.list(dir)) {
                entries = stream.toList();
            } catch (AccessDeniedException e) {
                report.error(group.getName(), dir.toString(), I18n.t("collect.err.dir_denied"));
                continue;
            } catch (IOException e) {
                report.error(group.getName(), dir.toString(), I18n.t("collect.err.dir_read", e.getMessage()));
                continue;
            }
            for (Path entry : entries) {
                try {
                    if (Files.isDirectory(entry)) {
                        if (recursive && !isSymlink(entry)) {
                            queue.add(entry);
                        }
                        continue;
                    }
                    if (Files.isRegularFile(entry) && FileFilters.accept(group, root, entry)
                            && keepByScript(scriptFilter, root, entry)) {
                        candidates.add(new Candidate(group, root, entry));
                        stat.matched++;
                    }
                } catch (IOException e) {
                    report.error(group.getName(), entry.toString(), I18n.t("collect.err.file_access", e.getMessage()));
                }
            }
        }
    }

    private boolean keepByScript(GroovyFilter filter, Path root, Path file) {
        if (filter == null) {
            return true;
        }
        String rel = FileFilters.relativePath(root, file);
        try {
            return filter.keep(file.toFile(), rel, file.getFileName().toString());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    I18n.t("collect.err.script", rel, e.getMessage()), e);
        }
    }

    private boolean isSymlink(Path p) throws IOException {
        return Files.isSymbolicLink(p);
    }

    private void writeCopy(ProjectConfig project, Path outputDir,
                           List<Candidate> candidates, CollectReport report) {
        for (Candidate c : candidates) {
            CollectReport.GroupStat stat = report.statFor(c.group().getName());
            String rel = FileFilters.relativePath(c.root(), c.file());
            Path dest = resolveChild(outputDir, subPathOf(c.group(), rel));
            if (dest == null) {
                report.error(c.group().getName(), rel, I18n.t("collect.err.path_escape"));
                continue;
            }
            try {
                if (Files.exists(dest)) {
                    if (project.getConflictPolicy() == com.filesummer.model.ConflictPolicy.SKIP) {
                        stat.skippedByConflict++;
                        continue;
                    }
                }
                Files.createDirectories(dest.getParent());
                Files.copy(c.file(), dest, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                // COPY_ATTRIBUTES can miss mtime across filesystems: write it explicitly.
                FileTime mtime = Files.getLastModifiedTime(c.file());
                Files.setLastModifiedTime(dest, mtime);
                stat.written++;
            } catch (AccessDeniedException e) {
                report.error(c.group().getName(), dest.toString(), I18n.t("collect.err.write_denied"));
            } catch (IOException e) {
                report.error(c.group().getName(), dest.toString(), I18n.t("collect.err.write_failed", e.getMessage()));
            }
        }
        report.setOutputLocation(outputDir.toString());
    }

    private void writeZip(ProjectConfig project, Path outputDir,
                          List<Candidate> candidates, CollectReport report) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String zipName = sanitizeZipBase(project.getName()) + "-" + stamp + ".zip";
        Path zipPath = outputDir.resolve(zipName);
        java.util.Set<String> seen = new java.util.HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Candidate c : candidates) {
                CollectReport.GroupStat stat = report.statFor(c.group().getName());
                String rel = FileFilters.relativePath(c.root(), c.file());
                String entryName = subPathOf(c.group(), rel);
                if (entryName == null) {
                    report.error(c.group().getName(), rel, I18n.t("collect.err.bad_entry"));
                    continue;
                }
                if (!seen.add(entryName)) {
                    if (project.getConflictPolicy() == com.filesummer.model.ConflictPolicy.SKIP) {
                        stat.skippedByConflict++;
                        continue;
                    }
                }
                ZipEntry entry = new ZipEntry(entryName);
                entry.setLastModifiedTime(Files.getLastModifiedTime(c.file()));
                zos.putNextEntry(entry);
                Files.copy(c.file(), zos);
                zos.closeEntry();
                stat.written++;
            }
        } catch (IOException e) {
            report.error(null, zipPath.toString(), I18n.t("collect.err.zip_failed", e.getMessage()));
            return;
        }
        report.setOutputLocation(zipPath.toString());
    }

    /** group subDir + relative path, '/' separated, null when it escapes the output root. */
    private String subPathOf(RuleGroup group, String rel) {
        String sub = group.getSubDir() == null ? "" : group.getSubDir().trim().replace('\\', '/');
        while (sub.startsWith("/")) {
            sub = sub.substring(1);
        }
        while (sub.endsWith("/")) {
            sub = sub.substring(0, sub.length() - 1);
        }
        String joined = sub.isEmpty() ? rel : sub + "/" + rel;
        for (String part : joined.split("/")) {
            if (part.equals("..")) {
                return null;
            }
        }
        return joined;
    }

    private Path resolveChild(Path base, String relSlash) {
        if (relSlash == null) {
            return null;
        }
        Path resolved = base.resolve(relSlash).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }

    private String sanitizeZipBase(String name) {
        String s = name == null || name.isBlank() ? "filesummer" : name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        return s.toLowerCase(Locale.ROOT);
    }
}
