package inspector.detect;

/**
 * What a detector concluded about one finding. The first three are stamp-guard's attribution
 * (§5.3); the MISS_ values are edit-miss's reading of what came before a failed edit on the same
 * path, which is the difference between a model that never looked, one that looked and still
 * misquoted, one that forgot its own change, and one retrying blind.
 */
public enum Category {
    DIRECT_MUTATION, VCS_RESTORE, EXTERNAL,
    REPEATED_MISS, MISS_AFTER_EDIT, MISS_AFTER_READ, MISS_AFTER_PARTIAL_READ, MISS_UNREAD
}
