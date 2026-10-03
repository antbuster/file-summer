package com.filesummer.model;

public enum ConflictPolicy {
    /** Overwrite an existing file at the same output path. */
    OVERWRITE,
    /** Keep the existing file, skip the new one. */
    SKIP
}
