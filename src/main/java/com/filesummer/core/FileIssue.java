package com.filesummer.core;

public class FileIssue {

    public enum Level { ERROR, WARN }

    private final Level level;
    private final String group;
    private final String path;
    private final String message;

    public FileIssue(Level level, String group, String path, String message) {
        this.level = level;
        this.group = group;
        this.path = path;
        this.message = message;
    }

    public Level getLevel() {
        return level;
    }

    public String getGroup() {
        return group;
    }

    public String getPath() {
        return path;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        String loc = (group == null ? "" : "[" + group + "] ") + (path == null ? "" : path);
        return level + " " + loc + " " + message;
    }
}
