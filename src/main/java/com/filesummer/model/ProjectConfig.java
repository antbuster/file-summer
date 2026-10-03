package com.filesummer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/** A user project: scan sources, filters, per-task recursion and one output location. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectConfig {

    private String name = "默认项目";
    private String outputDir = "";
    private ConflictPolicy conflictPolicy = ConflictPolicy.OVERWRITE;
    /** Legacy project-level flag; ProjectStore pushes it down to the rules once on load. */
    private boolean recursive = true;
    private List<RuleGroup> rules = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public ConflictPolicy getConflictPolicy() {
        return conflictPolicy;
    }

    public void setConflictPolicy(ConflictPolicy conflictPolicy) {
        this.conflictPolicy = conflictPolicy;
    }

    public boolean isRecursive() {
        return recursive;
    }

    public void setRecursive(boolean recursive) {
        this.recursive = recursive;
    }

    public List<RuleGroup> getRules() {
        return rules;
    }

    public void setRules(List<RuleGroup> rules) {
        this.rules = rules;
    }

    public RuleGroup newDefaultGroup() {
        RuleGroup g = new RuleGroup();
        g.setName("任务" + (rules.size() + 1));
        return g;
    }
}
