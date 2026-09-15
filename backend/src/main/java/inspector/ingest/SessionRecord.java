package inspector.ingest;

/**
 * cwd is the session's working directory, read from the session line root. It is kept in
 * memory only so the writer can relativize absolute paths into project-relative path_hints
 * (§6 privacy rule); it is never persisted itself.
 */
public record SessionRecord(String id, String sourceFile, String projectSlug, String schema,
                            long startedAt, Long endedAt, String agentPreset, Integer delegationDepth,
                            String model, Integer contextWindow, int fatalTurns, String cwd) {
}
