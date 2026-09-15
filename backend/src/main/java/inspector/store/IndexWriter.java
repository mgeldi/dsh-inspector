package inspector.store;

import inspector.detect.ErrorPlanes;
import inspector.detect.Finding;
import inspector.ingest.SessionRecord;
import inspector.ingest.SessionSource;
import inspector.ingest.ShellEvidence;
import inspector.ingest.StreamFacts;
import inspector.ingest.ToolCallRecord;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The write path of the index. One stream = one transaction: delete the stream's rows,
 * then insert them fresh. Re-indexing the same (sessionId, sourceFile) is idempotent —
 * delete-then-insert replaces what was there, across all six tables, without upserts.
 *
 * <p>Every path goes through {@link Paths#hint} before anything reaches disk, so a raw
 * absolute path can never end up in the database (DESIGN.md §4.1, §6). occurred_at is the
 * finding's own event time, persisted verbatim — nothing in this class interpolates a time.
 *
 * <p>The database is a derived cache of the corpus, not a system of record: everything in
 * it is reproducible by re-reading the logs, so a schema change is an invalidation event,
 * not a migration. That is why {@link #resetIfStale(String)} empties the whole index when
 * the stored {@code meta.schema_version} no longer matches, and the whole reason
 * DESIGN.md §4.2 could ship without a migration engine.
 */
@Component
public final class IndexWriter {

    private static final Logger LOG = LoggerFactory.getLogger(IndexWriter.class);

    /** What one write produced. The orchestrator sums these into its summary line. */
    public record Written(int steps, int toolCalls, int findings, int evidenceRows) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public IndexWriter(final JdbcTemplate jdbc, final PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }

    public Written writeStream(final SessionSource source, final StreamFacts facts,
                               final List<Finding> findings, final String harnessVersion,
                               final boolean storeEvidence, final long indexedAt) {
        return tx.execute(status -> write(facts, findings, harnessVersion, storeEvidence, indexedAt));
    }

    private Written write(final StreamFacts facts, final List<Finding> findings,
                          final String harnessVersion, final boolean storeEvidence,
                          final long indexedAt) {
        // The key comes from the facts the ingestor resolved: it is the same (sessionId,
        // sourceFile) the scanner produced, and it is what a re-index deletes by.
        final String sessionId = facts.session().id();
        final String sourceFile = facts.session().sourceFile();
        final String cwd = facts.session().cwd();
        final List<Long> findingIds = new ArrayList<>();

        deleteStream(sessionId, sourceFile);

        final SessionRecord session = facts.session();
        jdbc.update(
                "insert into session (id, source_file, project_slug, schema, started_at, ended_at,"
                        + " agent_preset, delegation_depth, model, context_window, harness_version,"
                        + " version_inferred, indexed_at, fatal_turns) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                session.id(), session.sourceFile(), session.projectSlug(), session.schema(),
                session.startedAt(), session.endedAt(), session.agentPreset(),
                session.delegationDepth(), session.model(), session.contextWindow(),
                harnessVersion, 1, indexedAt, session.fatalTurns());

        final int steps = insertSteps(sessionId, sourceFile, facts);
        final int toolCalls = insertToolCalls(sessionId, sourceFile, cwd, facts);
        final int findingsWritten = insertFindings(sessionId, sourceFile, cwd, findings, findingIds);
        final int evidenceRows = storeEvidence ? insertEvidence(findings, findingIds, cwd) : 0;

        return new Written(steps, toolCalls, findingsWritten, evidenceRows);
    }

    private void deleteStream(final String sessionId, final String sourceFile) {
        // shell_evidence first: its rows are owned by finding ids that are about to disappear.
        jdbc.update("delete from shell_evidence where finding_id in"
                        + " (select id from finding where session_id = ? and source_file = ?)",
                sessionId, sourceFile);
        jdbc.update("delete from finding where session_id = ? and source_file = ?",
                sessionId, sourceFile);
        jdbc.update("delete from tool_call where session_id = ? and source_file = ?",
                sessionId, sourceFile);
        jdbc.update("delete from step where session_id = ? and source_file = ?",
                sessionId, sourceFile);
        jdbc.update("delete from session where id = ? and source_file = ?",
                sessionId, sourceFile);
    }

    private int insertSteps(final String sessionId, final String sourceFile, final StreamFacts facts) {
        if (facts.steps().isEmpty()) {
            return 0;
        }
        final List<Object[]> rows = new ArrayList<>(facts.steps().size());
        for (final var step : facts.steps()) {
            rows.add(new Object[]{sessionId, sourceFile, step.turn(), step.step(),
                    step.startedAt(), step.endedAt(), step.inputTokens(), step.outputTokens(),
                    step.decodeTps(), step.ttftMs(), step.timingSource()});
        }
        return jdbc.batchUpdate(
                "insert into step (session_id, source_file, turn, step, started_at, ended_at,"
                        + " input_tokens, output_tokens, decode_tps, ttft_ms, timing_source)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?)", rows).length;
    }

    private int insertToolCalls(final String sessionId, final String sourceFile, final String cwd,
                                final StreamFacts facts) {
        if (facts.toolCalls().isEmpty()) {
            return 0;
        }
        final List<Object[]> rows = new ArrayList<>(facts.toolCalls().size());
        for (final ToolCallRecord call : facts.toolCalls()) {
            rows.add(new Object[]{sessionId, sourceFile, call.turn(), call.step(), call.seq(),
                    call.name(), call.startedAt(), call.endedAt(), call.durationMs(),
                    call.errorCode(),
                    call.errorCode() == null ? null : ErrorPlanes.ofToolCode(call.errorCode()).name(),
                    Paths.hint(call.absolutePath(), cwd), call.outcomeOnly() ? 1 : 0});
        }
        return jdbc.batchUpdate(
                "insert into tool_call (session_id, source_file, turn, step, seq, name, started_at,"
                        + " ended_at, duration_ms, error_code, plane, path_hint, outcome_only)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?,?,?)", rows).length;
    }

    private int insertFindings(final String sessionId, final String sourceFile, final String cwd,
                               final List<Finding> findings, final List<Long> findingIds) {
        if (findings.isEmpty()) {
            return 0;
        }
        final List<Object[]> rows = new ArrayList<>(findings.size());
        for (final Finding finding : findings) {
            rows.add(new Object[]{sessionId, sourceFile, finding.detector(), finding.plane().name(),
                    finding.category() == null ? null : finding.category().name(), finding.code(),
                    finding.confidence(), Paths.hint(finding.absolutePath(), cwd),
                    finding.seq(), finding.staleSeq(), finding.causeSeq(),
                    finding.occurredAt(), finding.summary()});
        }
        final int written = jdbc.batchUpdate(
                "insert into finding (session_id, source_file, detector, plane, category, code,"
                        + " confidence, path_hint, seq, stale_seq, cause_seq, occurred_at, summary)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?,?,?)", rows).length;

        // Evidence rows need the finding ids. After delete-then-insert, this stream's finding
        // rows are exactly the ones just written, and AUTOINCREMENT ids rise with input order,
        // so ordering by id recovers the input order row for row.
        jdbc.query("select id from finding where session_id = ? and source_file = ? order by id",
                (rs, rowNum) -> findingIds.add(rs.getLong(1)), sessionId, sourceFile);
        return written;
    }

    private int insertEvidence(final List<Finding> findings, final List<Long> findingIds,
                               final String cwd) {
        final List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            final Finding finding = findings.get(i);
            for (final ShellEvidence evidence : finding.evidence()) {
                rows.add(new Object[]{findingIds.get(i), evidence.seq(),
                        evidence.verbClass().name(),
                        Paths.hint(evidencePath(finding, evidence), cwd),
                        evidence.excerpt().value()});
            }
        }
        if (rows.isEmpty()) {
            return 0;
        }
        return jdbc.batchUpdate(
                "insert into shell_evidence (finding_id, seq, verb_class, path_hint, excerpt_redacted)"
                        + " values (?,?,?,?,?)", rows).length;
    }

    /**
     * The evidence row carries the referenced path the evidence matched the finding on —
     * absolutely when the command named the same file, by basename otherwise. The match is
     * why this evidence was attached, so it is the path the drawer should show.
     */
    private static String evidencePath(final Finding finding, final ShellEvidence evidence) {
        final String failed = finding.absolutePath();
        for (final String referenced : evidence.referencedPaths()) {
            if (failed != null && failed.equals(referenced)) {
                return referenced;
            }
        }
        if (failed != null) {
            final String base = baseName(failed);
            for (final String referenced : evidence.referencedPaths()) {
                if (base.equals(baseName(referenced))) {
                    return referenced;
                }
            }
        }
        return evidence.referencedPaths().stream().min(String::compareTo).orElse(null);
    }

    private static String baseName(final String path) {
        final int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public void seedMeta(final String schemaVersion) {
        jdbc.update("insert into meta (key, value) values ('schema_version', ?)"
                + " on conflict(key) do update set value = excluded.value", schemaVersion);
    }

    /**
     * The invalidation behind the no-migration-engine decision (DESIGN.md §4.2). When the
     * stored schema version does not match the one the running build expects — including
     * "no value stored" — every table is emptied, child-to-parent so the order holds with
     * {@code foreign_keys=on}, and the version is re-seeded, all in one transaction so a
     * crash mid-reset cannot leave a half-emptied database that looks like a valid empty
     * index. Only a count is logged: how many streams were discarded, never a corpus path
     * or content.
     *
     * @return how many streams were discarded; zero when the stored version already matches
     */
    public int resetIfStale(final String expectedSchemaVersion) {
        return tx.execute(status -> {
            final String stored = storedSchemaVersion();
            if (expectedSchemaVersion.equals(stored)) {
                return 0;
            }
            final int discarded = jdbc.queryForObject("select count(*) from session", Integer.class);
            // child-to-parent: evidence rows reference the findings that own them, and
            // findings, tool calls and steps reference the sessions that own them
            jdbc.update("delete from shell_evidence");
            jdbc.update("delete from finding");
            jdbc.update("delete from tool_call");
            jdbc.update("delete from step");
            jdbc.update("delete from session");
            seedMeta(expectedSchemaVersion);
            LOG.info("index schema version is stale (stored: {}, expected: {}); reset the index,"
                    + " discarding {} streams", stored, expectedSchemaVersion, discarded);
            return discarded;
        });
    }

    /** The stored schema version; null on a database no index run has versioned yet. */
    private String storedSchemaVersion() {
        return jdbc.query("select value from meta where key = 'schema_version'",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    /** Zero on a fresh database. The startup rule indexes only when this returns nothing. */
    public int countSessions() {
        // Distinct ids: a session holding both log conventions occupies two rows, and "sessions"
        // has to mean sessions wherever it is printed. The startup gate only cares about zero
        // versus non-zero, which this preserves.
        return jdbc.queryForObject("select count(distinct id) from session", Integer.class);
    }
}
