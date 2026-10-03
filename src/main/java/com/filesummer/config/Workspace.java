package com.filesummer.config;

import com.filesummer.model.ProjectConfig;

import java.util.ArrayList;
import java.util.List;

/** Root of the persisted file: project list plus the selected project index. */
public class Workspace {

    private int schemaVersion = 1;
    private List<ProjectConfig> projects = new ArrayList<>();
    private int currentProjectIndex = 0;
    /** UI language tag, "zh_CN" (default), "ja_JP" or "en_US". */
    private String language = "zh_CN";

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getLanguage() {
        return language == null || language.isBlank() ? "zh_CN" : language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public List<ProjectConfig> getProjects() {
        return projects;
    }

    public void setProjects(List<ProjectConfig> projects) {
        this.projects = projects;
    }

    public int getCurrentProjectIndex() {
        return currentProjectIndex;
    }

    public void setCurrentProjectIndex(int currentProjectIndex) {
        this.currentProjectIndex = currentProjectIndex;
    }

    public ProjectConfig getCurrentProject() {
        if (projects.isEmpty()) {
            ProjectConfig p = new ProjectConfig();
            projects.add(p);
        }
        int i = Math.max(0, Math.min(currentProjectIndex, projects.size() - 1));
        return projects.get(i);
    }

    public static Workspace withDefaults() {
        Workspace w = new Workspace();
        ProjectConfig p = new ProjectConfig();
        p.setName("默认项目");
        p.getRules().add(p.newDefaultGroup());
        w.getProjects().add(p);
        return w;
    }
}
