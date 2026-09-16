package inspector.store;

import inspector.detect.ErrorPlanes;
import inspector.detect.Finding;
import inspector.ingest.SessionRecord;
import inspector.ingest.SessionSource;
import inspector.ingest.ShellEvidence;
import inspector.ingest.StreamFacts;
import inspector.ingest.ToolCallRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
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
 * not a migration. That is why {@link #resetIfStale(String)} drops and re-creates the whole
 * schema when the stored {@code meta.schema_version} no longer matches — dropping, not just
 * emptying, because a table that already exists is never redefined by the DDL — and it is the
 * whole reason DESIGN.md §4.2 could ship without a migration engine.
 */
@Component
public final class IndexWriter {

    private static final Logger LOG = LoggerFactory.getLogger(IndexWriter.class);

    /** What one write produced. The orchestrator sums these into its summary line. */
    public record Written(int steps, int toolCalls, int findings, int evidenceRows) {
    }

    /**
     * What one stream is: the same {@code (sessionId, sourceFile)} pair {@link #writeStream}
     * deletes by, and the pair the whole schema is keyed on.
     */
    public record StreamKey(String sessionId, String sourceFile) {
    }

    /**
     * The tables {@code schema.sql} owns, child-to-parent — the same order {@link #wipe()}
     * deletes in, for the same reason: with {@code foreign_keys=on} a parent cannot go first.
     */
    private static final List<String> TABLES_CHILD_FIRST =
            List.of("shell_evidence", "finding", "tool_call", "step", "session", "meta");

    /**
     * Where the DDL lives, as one string. Spring applies it at boot from
     * {@code spring.sql.init.schema-locations}; this class re-applies it after a stale-version
     * drop. The pairing is asserted by name in SchemaTest — two sources for one schema is
     * exactly the drift that makes a comment like "the tables match schema.sql" false.
     */
    private static final String SCHEMA_DDL_CLASSPATH = "schema.sql";

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

    /**
     * Discards every stream of <em>this</em> corpus that the run did not write, so an index run
     * makes the database equal to the corpus instead of merging into it. The database is a
     * derived cache (DESIGN.md §4.2), which means the corpus is the only thing it is ever
     * allowed to describe: a session removed from the corpus has to disappear from the
     * dashboard too, or the screen shows findings no log contains and contradicts the summary
     * line of the run that just finished.
     *
     * <p>Nothing is deleted when the index was seeded from a different corpus. Those rows are
     * not this run's corpus, and a run that never read them cannot judge that they vanished —
     * switching corpora is {@link #resetIfCorpusChanged}'s job, pruning is this one's. Silently
     * deleting them here would also turn a stray {@code --inspector.corpus} typo into the loss
     * of an index the operator never meant to touch.
     *
     * <p>Child-to-parent per stream, the same order {@link #wipe()} uses, in one transaction.
     * Walking the {@code session} table is enough to find every vanished stream: one stream is
     * one transaction in {@link #writeStream}, so its rows exist together or not at all and a
     * stream with no session row has nothing else either.
     *
     * @param corpus   the corpus this run indexed, compared against the recorded one
     * @param written  the streams this run wrote
     * @return how many streams were discarded; zero when the index already matches the corpus
     */
    public int pruneToWritten(final Path corpus, final Collection<StreamKey> written) {
        return tx.execute(status -> {
            final String stored = storedCorpus();
            final String expected = corpus.toAbsolutePath().normalize().toString();
            if (stored == null || !expected.equals(stored)) {
                return 0;
            }
            final Set<StreamKey> keep = Set.copyOf(written);
            final List<StreamKey> vanished = jdbc.query(
                    "select id, source_file from session order by id, source_file",
                    (rs, rowNum) -> new StreamKey(rs.getString(1), rs.getString(2)))
                    .stream().filter(key -> !keep.contains(key)).toList();
            for (final StreamKey key : vanished) {
                deleteStream(key.sessionId(), key.sourceFile());
            }
            // counts only: a source_file is a path inside the corpus
            if (!vanished.isEmpty()) {
                LOG.info("the corpus no longer holds {} streams this index still described;"
                        + " pruned them", vanished.size());
            }
            return vanished.size();
        });
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
     * Records which corpus this index was built from. The database is keyed by nothing that
     * says where its rows came from, so a file that once held a live index would happily
     * serve 389 findings to a run configured for the fixture corpus — a dashboard quietly
     * describing data it never read. Never logged and never served: the path carries the
     * username, so it stays inside the gitignored database file.
     */
    public void seedCorpus(final String absoluteCorpusPath) {
        jdbc.update("insert into meta (key, value) values ('corpus', ?)"
                + " on conflict(key) do update set value = excluded.value", absoluteCorpusPath);
    }

    /**
     * Empties the index when it was built from a different corpus than the one now
     * configured. A database with sessions but no recorded corpus also resets: an index
     * of unknown provenance cannot be trusted to describe the configured corpus, and
     * re-indexing costs seconds (DESIGN.md §12).
     *
     * @return how many streams were discarded; zero when the index already matches
     */
    public int resetIfCorpusChanged(final Path corpus) {
        return tx.execute(status -> {
            final String stored = storedCorpus();
            final String expected = corpus.toAbsolutePath().normalize().toString();
            if (expected.equals(stored)) {
                return 0;
            }
            final int discarded = jdbc.queryForObject("select count(distinct id) from session", Integer.class);
            if (discarded == 0 && stored == null) {
                // nothing in it to invalidate; the corpus is recorded by the run that fills it
                return 0;
            }
            final int streams = jdbc.queryForObject("select count(*) from session", Integer.class);
            wipe();
            LOG.info("the index was built from a different corpus than the one configured;"
                    + " reset it, discarding {} streams and {} sessions", streams, discarded);
            return discarded;
        });
    }

    /**
     * The invalidation behind the no-migration-engine decision (DESIGN.md §4.2). When the
     * stored schema version does not match the one the running build expects — including
     * "no value stored" — the tables are dropped and re-created from the DDL, child-to-parent
     * so the order holds under {@code foreign_keys=on}, and the version is re-seeded, all in
     * one transaction so a crash mid-reset cannot leave a half-emptied database that looks like
     * a valid empty index. Only a count is logged: how many streams were discarded, never a
     * corpus path or content.
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
            dropAndRecreate();
            seedMeta(expectedSchemaVersion);
            LOG.info("index schema version is stale (stored: {}, expected: {}); dropped and"
                    + " re-created the schema, discarding {} streams",
                    stored, expectedSchemaVersion, discarded);
            return discarded;
        });
    }

    /**
     * Drop every table, then re-apply the DDL — the only way a schema change reaches a database
     * file that already exists.
     *
     * <p>Emptying the tables is not enough and cannot be made enough. {@code schema.sql} is
     * {@code CREATE TABLE IF NOT EXISTS}, so a table that is already there is skipped, and
     * SQLite has no {@code ALTER TABLE ADD CONSTRAINT}: anything a version bump is supposed to
     * deliver — the foreign keys in {@code schema.sql} are the case that proved it — would land
     * only on fresh installs, and the file would keep claiming a constraint it does not have.
     * Re-applying the same classpath script Spring ran at boot keeps one DDL definition instead
     * of a Java copy that can drift from it.
     *
     * <p>Two details are load-bearing rather than tidy. The drops go child-to-parent because
     * {@code DROP TABLE} runs an implicit {@code DELETE FROM} that respects foreign keys when
     * they are enabled (sqlite.org/foreignkeys.html §5), so dropping {@code session} first would
     * be refused by the rows still pointing at it. And the script runs on this transaction's own
     * connection — borrowing a second one from the pool would block on the write lock this
     * transaction holds, and a connection outside the transaction would not even be in the same
     * schema state when the next statement lands.
     */
    private void dropAndRecreate() {
        TABLES_CHILD_FIRST.forEach(table -> jdbc.execute("drop table if exists " + table));
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource(SCHEMA_DDL_CLASSPATH), StandardCharsets.UTF_8));
            return null;
        });
    }

    /** Child-to-parent, so the order holds with {@code foreign_keys=on}. */
    private void wipe() {
        // evidence rows reference the findings that own them; findings, tool calls and
        // steps reference the sessions that own them
        jdbc.update("delete from shell_evidence");
        jdbc.update("delete from finding");
        jdbc.update("delete from tool_call");
        jdbc.update("delete from step");
        jdbc.update("delete from session");
    }

    /** The stored schema version; null on a database no index run has versioned yet. */
    private String storedSchemaVersion() {
        return jdbc.query("select value from meta where key = 'schema_version'",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    /** The corpus the last run recorded; null on a database no index run has attributed yet. */
    private String storedCorpus() {
        return jdbc.query("select value from meta where key = 'corpus'",
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
