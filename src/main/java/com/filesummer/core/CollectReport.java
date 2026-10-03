package com.filesummer.core;

import java.util.ArrayList;
import java.util.List;

public class CollectReport {

    public static class GroupStat {
        public final String group;
        public int matched;
        public int written;
        public int skippedByConflict;

        public GroupStat(String group) {
            this.group = group;
        }
    }

    private final List<GroupStat> stats = new ArrayList<>();
    private final List<FileIssue> issues = new ArrayList<>();
    private String outputLocation = "";

    public List<GroupStat> getStats() {
        return stats;
    }

    public List<FileIssue> getIssues() {
        return issues;
    }

    public String getOutputLocation() {
        return outputLocation;
    }

    public void setOutputLocation(String outputLocation) {
        this.outputLocation = outputLocation;
    }

    public int totalMatched() {
        return stats.stream().mapToInt(s -> s.matched).sum();
    }

    public int totalWritten() {
        return stats.stream().mapToInt(s -> s.written).sum();
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.getLevel() == FileIssue.Level.ERROR);
    }

    public GroupStat statFor(String group) {
        for (GroupStat s : stats) {
            if (s.group.equals(group)) {
                return s;
            }
        }
        GroupStat s = new GroupStat(group);
        stats.add(s);
        return s;
    }

    public void error(String group, String path, String message) {
        issues.add(new FileIssue(FileIssue.Level.ERROR, group, path, message));
    }

    public void warn(String group, String path, String message) {
        issues.add(new FileIssue(FileIssue.Level.WARN, group, path, message));
    }
}
