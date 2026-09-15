package inspector.ingest;

import java.util.List;

/**
 * The typed output of one ingest pass. Everything is a count or a reference, never raw text;
 * the only content-like value is the RedactedExcerpt inside ShellEvidence, and only up to 20
 * references per command. DESIGN.md §4.1.
 */
public record StreamFacts(SessionRecord session, List<StepRecord> steps,
                          List<ToolCallRecord> toolCalls, List<FileTouch> touches,
                          List<ShellEvidence> shell, List<ErrorEvent> errors,
                          List<FatalTurn> fatalTurns, List<RetryEvent> retries,
                          long parseFailures) {
}
