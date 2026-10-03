package com.filesummer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * One task group ("任务组" in UI wording): several source directories mapped
 * to one output sub-directory, with its own filter conditions.
 * JSON field names are part of the persisted schema - do not rename.
 * JavaFX properties power direct TableView editing; Jackson uses the
 * @JsonProperty accessors below.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuleGroup {

    private final StringProperty name = new SimpleStringProperty("任务");
    private final BooleanProperty enabled = new SimpleBooleanProperty(true);
    /** Per-task scan depth; older configs only had a project-level flag (migrated in ProjectConfig). */
    private boolean recursive = true;
    private List<String> sourceDirs = new ArrayList<>();
    private final StringProperty subDir = new SimpleStringProperty("");
    /** Kept only so old configs round-trip without data loss. */
    private String legacyOutputDir = "";
    private List<String> includeSuffixes = new ArrayList<>();
    private List<String> excludeSuffixes = new ArrayList<>();
    private List<String> excludeDirFragments = new ArrayList<>();
    /** Date filter mode: null/"NONE" = off, "AFTER" = not earlier than dateFrom,
     *  "BEFORE" = earlier than dateTo, "RANGE" = dateFrom..dateTo (both days inclusive). */
    private String dateMode;
    /** ISO date "yyyy-MM-dd", lower bound day (inclusive). */
    private String dateFrom;
    /** ISO date "yyyy-MM-dd", upper bound day. */
    private String dateTo;
    /** Optional Groovy keep-predicate body (vars: f=java.io.File, relPath, name);
     *  null/blank = script filter off. Same semantics in-app and in generated Gradle scripts. */
    private String filterScript;

    @JsonProperty("name")
    public String getName() {
        return name.get();
    }

    public void setName(String value) {
        name.set(value == null ? "任务" : value);
    }

    public StringProperty nameProperty() {
        return name;
    }

    @JsonProperty("enabled")
    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public BooleanProperty enabledProperty() {
        return enabled;
    }

    public boolean isRecursive() {
        return recursive;
    }

    public void setRecursive(boolean recursive) {
        this.recursive = recursive;
    }

    public List<String> getSourceDirs() {
        return sourceDirs;
    }

    public void setSourceDirs(List<String> sourceDirs) {
        this.sourceDirs = sourceDirs;
    }

    @JsonProperty("subDir")
    public String getSubDir() {
        return subDir.get();
    }

    public void setSubDir(String value) {
        subDir.set(value == null ? "" : value);
    }

    public StringProperty subDirProperty() {
        return subDir;
    }

    @JsonProperty("legacyOutputDir")
    public String getLegacyOutputDir() {
        return legacyOutputDir;
    }

    public void setLegacyOutputDir(String legacyOutputDir) {
        this.legacyOutputDir = legacyOutputDir;
    }

    public List<String> getIncludeSuffixes() {
        return includeSuffixes;
    }

    public void setIncludeSuffixes(List<String> includeSuffixes) {
        this.includeSuffixes = includeSuffixes;
    }

    public List<String> getExcludeSuffixes() {
        return excludeSuffixes;
    }

    public void setExcludeSuffixes(List<String> excludeSuffixes) {
        this.excludeSuffixes = excludeSuffixes;
    }

    public List<String> getExcludeDirFragments() {
        return excludeDirFragments;
    }

    public void setExcludeDirFragments(List<String> excludeDirFragments) {
        this.excludeDirFragments = excludeDirFragments;
    }

    @JsonProperty("dateMode")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getDateMode() {
        return dateMode;
    }

    public void setDateMode(String dateMode) {
        this.dateMode = dateMode;
    }

    @JsonProperty("dateFrom")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getDateFrom() {
        return dateFrom;
    }

    public void setDateFrom(String dateFrom) {
        this.dateFrom = dateFrom;
    }

    @JsonProperty("dateTo")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getDateTo() {
        return dateTo;
    }

    public void setDateTo(String dateTo) {
        this.dateTo = dateTo;
    }

    @JsonProperty("filterScript")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getFilterScript() {
        return filterScript;
    }

    public void setFilterScript(String filterScript) {
        this.filterScript = filterScript;
    }

    /** Migration-only accessor: configs written before date modes stored a single
     *  "modifiedSince"; reading it maps onto AFTER + dateFrom. Never serialized out. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getModifiedSince() {
        return null;
    }

    @JsonProperty("modifiedSince")
    public void setModifiedSince(String modifiedSince) {
        if (modifiedSince != null && !modifiedSince.isBlank()) {
            this.dateMode = "AFTER";
            this.dateFrom = modifiedSince.trim();
        }
    }

    @Override
    public String toString() {
        return getName();
    }
}
