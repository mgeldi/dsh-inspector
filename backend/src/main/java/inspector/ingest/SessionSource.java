package inspector.ingest;

import java.nio.file.Path;

/**
 * One stream. §3.2: the same session id can exist in two files with independent seq spaces, so
 * the unit of ingestion is (sessionId, sourceFile) — never sessionId alone.
 */
public record SessionSource(Path file, String sessionId, String projectSlug,
                            Convention convention, String sourceFile) {
}
