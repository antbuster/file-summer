package com.filesummer.core;

import com.filesummer.model.RuleGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** Filter predicates shared by the in-app collector and the generated Gradle semantics. */
public final class FileFilters {

    private FileFilters() {
    }

    /** Parses "yyyy-MM-dd" (trimmed); null/blank/invalid means "no date limit". */
    public static LocalDate parseDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static long startOfDayMillis(LocalDate date) {
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * Effective date window for a group as {minInclusive, maxExclusive} epoch millis,
     * or null when no date filter applies. Long.MIN_VALUE/MAX_VALUE act as open ends.
     * Missing/invalid dates make the whole filter inert (never filter everything out).
     */
    public static long[] dateBoundsMillis(RuleGroup group) {
        String mode = group.getDateMode();
        if (mode == null || mode.isBlank() || "NONE".equalsIgnoreCase(mode.trim())) {
            return null;
        }
        LocalDate from = parseDate(group.getDateFrom());
        LocalDate to = parseDate(group.getDateTo());
        switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "AFTER":
                return from == null ? null : new long[]{startOfDayMillis(from), Long.MAX_VALUE};
            case "BEFORE":
                return to == null ? null : new long[]{Long.MIN_VALUE, startOfDayMillis(to)};
            case "RANGE":
                if (from == null || to == null) {
                    return null;
                }
                long lo = startOfDayMillis(from);
                long hi = startOfDayMillis(to.plusDays(1));
                return hi <= lo ? null : new long[]{lo, hi};
            default:
                return null;
        }
    }

    public static String normalizeSuffix(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        return s;
    }

    /** Relative path of {@code file} against {@code root}, always using '/' separators. */
    public static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * Acceptance test. Directory fragments are matched against the RELATIVE path
     * (never the absolute path), case-insensitively; exclusions win over inclusions.
     */
    public static boolean accept(RuleGroup group, Path root, Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String suffix = dot >= 0 ? name.substring(dot + 1) : "";
        String rel = relativePath(root, file).toLowerCase(Locale.ROOT);

        List<String> excludeSuffixes = group.getExcludeSuffixes();
        if (excludeSuffixes != null && !suffix.isEmpty()
                && excludeSuffixes.stream().map(FileFilters::normalizeSuffix).anyMatch(suffix::equals)) {
            return false;
        }

        List<String> fragments = group.getExcludeDirFragments();
        if (fragments != null) {
            for (String frag : fragments) {
                String f = frag == null ? "" : frag.trim().toLowerCase(Locale.ROOT);
                if (!f.isEmpty() && rel.contains(f)) {
                    return false;
                }
            }
        }

        long[] bounds = dateBoundsMillis(group);
        if (bounds != null) {
            try {
                long mtime = Files.getLastModifiedTime(file).toMillis();
                if (mtime < bounds[0] || mtime >= bounds[1]) {
                    return false;
                }
            } catch (IOException e) {
                // unreadable timestamp: do not filter on date, later copy will report the error
            }
        }

        List<String> includes = group.getIncludeSuffixes();
        if (includes != null && !includes.isEmpty()) {
            return !suffix.isEmpty()
                    && includes.stream().map(FileFilters::normalizeSuffix).anyMatch(suffix::equals);
        }
        return true;
    }
}
