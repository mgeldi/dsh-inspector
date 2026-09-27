package inspector.ingest;

/**
 * cwd is the session's working directory, read from the session line root. It is kept in
 * memory only so the writer can relativize absolute paths into project-relative path_hints
 * (§6 privacy rule); it is never persisted itself.
 *
 * <p>{@code provider} is the last-seen {@code request/context.provider}. It is what tells two
 * routes apart when both serve the same model id — on the measured install the orchestrator and
 * every subagent report one id between them, and only the provider says which is which.
 */
public record SessionRecord(String id, String sourceFile, String projectSlug, String schema,
                            long startedAt, Long endedAt, String agentPreset, Integer delegationDepth,
                            String model, String provider, Integer contextWindow, int fatalTurns,
                            String cwd) {
}
