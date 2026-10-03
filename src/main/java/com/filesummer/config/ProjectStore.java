package com.filesummer.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.filesummer.model.ConflictPolicy;
import com.filesummer.model.ProjectConfig;
import com.filesummer.model.RuleGroup;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Persists {@link Workspace} to ~/.file-summer/projects.json.
 * Corrupt or missing files never throw: the store falls back to a
 * backup copy or to defaults and reports which recovery happened.
 */
public class ProjectStore {

    public enum LoadStatus {
        OK,
        CREATED_DEFAULTS,
        RESTORED_FROM_BACKUP,
        CORRUPTED_RESET_TO_DEFAULTS
    }

    public record LoadResult(Workspace workspace, LoadStatus status, String message) {
    }

    private final Path configDir;
    private final ObjectMapper mapper;

    public ProjectStore() {
        this(Paths.get(System.getProperty("user.home"), ".file-summer"));
    }

    public ProjectStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public Path configFile() {
        return configDir.resolve("projects.json");
    }

    public LoadResult load() {
        Path main = configFile();
        Path bak = configFile().resolveSibling("projects.json.bak");
        if (!Files.exists(main)) {
            if (Files.exists(bak)) {
                Workspace w = readQuietly(bak);
                if (w != null) {
                    return new LoadResult(w, LoadStatus.RESTORED_FROM_BACKUP,
                            com.filesummer.i18n.I18n.t("store.restored_missing"));
                }
            }
            return new LoadResult(Workspace.withDefaults(), LoadStatus.CREATED_DEFAULTS,
                    com.filesummer.i18n.I18n.t("store.created"));
        }
        Workspace w = readQuietly(main);
        if (w != null) {
            sanitize(w);
            return new LoadResult(w, LoadStatus.OK, com.filesummer.i18n.I18n.t("store.ok"));
        }
        if (Files.exists(bak)) {
            Workspace fromBak = readQuietly(bak);
            if (fromBak != null) {
                sanitize(fromBak);
                return new LoadResult(fromBak, LoadStatus.RESTORED_FROM_BACKUP,
                        com.filesummer.i18n.I18n.t("store.restored_corrupt"));
            }
        }
        return new LoadResult(Workspace.withDefaults(), LoadStatus.CORRUPTED_RESET_TO_DEFAULTS,
                com.filesummer.i18n.I18n.t("store.reset"));
    }

    public synchronized void save(Workspace workspace) throws IOException {
        Files.createDirectories(configDir);
        Path main = configFile();
        Path tmp = main.resolveSibling("projects.json.tmp");
        if (Files.exists(main)) {
            Files.copy(main, main.resolveSibling("projects.json.bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        mapper.writeValue(tmp.toFile(), workspace);
        try {
            Files.move(tmp, main, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, main, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Called when load ends in CORRUPTED_RESET_TO_DEFAULTS so the bad file is not lost. */
    public void quarantineCorruptFile() {
        Path main = configFile();
        if (Files.exists(main)) {
            try {
                Files.move(main, main.resolveSibling("projects.json.corrupt"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // best-effort; the recovery message already told the user what happened
            }
        }
    }

    private Workspace readQuietly(Path file) {
        try {
            return mapper.readValue(file.toFile(), Workspace.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Fill in values for fields missing from older/partial configs. */
    private void sanitize(Workspace w) {
        if (w.getProjects().isEmpty()) {
            w.getProjects().add(new ProjectConfig());
        }
        for (ProjectConfig p : w.getProjects()) {
            if (p.getName() == null) {
                p.setName("未命名项目");
            }
            if (p.getOutputDir() == null) {
                p.setOutputDir("");
            }
            if (p.getConflictPolicy() == null) {
                p.setConflictPolicy(ConflictPolicy.OVERWRITE);
            }
            p.getRules().removeIf(java.util.Objects::isNull);
            if (p.getRules().isEmpty()) {
                p.getRules().add(p.newDefaultGroup());
            }
            for (RuleGroup g : p.getRules()) {
                if (g.getName() == null) {
                    g.setName("任务");
                }
                if (g.getSourceDirs() == null) {
                    g.setSourceDirs(new java.util.ArrayList<>());
                }
                if (g.getIncludeSuffixes() == null) {
                    g.setIncludeSuffixes(new java.util.ArrayList<>());
                }
                if (g.getExcludeSuffixes() == null) {
                    g.setExcludeSuffixes(new java.util.ArrayList<>());
                }
                if (g.getExcludeDirFragments() == null) {
                    g.setExcludeDirFragments(new java.util.ArrayList<>());
                }
                if (g.getSubDir() == null) {
                    g.setSubDir("");
                }
            }
            // Legacy configs had a single project-level recursion flag; push it down once.
            if (!p.isRecursive()) {
                for (RuleGroup g : p.getRules()) {
                    g.setRecursive(false);
                }
                p.setRecursive(true);
            }
        }
        w.setCurrentProjectIndex(Math.max(0, Math.min(w.getCurrentProjectIndex(), w.getProjects().size() - 1)));
    }
}
