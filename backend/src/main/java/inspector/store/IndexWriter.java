package inspector.store;

import inspector.detect.ErrorPlanes;
import inspector.detect.Finding;
import inspector.ingest.SessionRecord;
import inspector.ingest.SessionSource;
import inspector.ingest.ShellEvidence;
import inspector.ingest.StepRecord;
import inspector.ingest.StreamFacts;
import inspector.ingest.ToolCallRecord;
import inspector.store.entity.EvidenceId;
import inspector.store.entity.FindingEntity;
import inspector.store.entity.MetaEntity;
import inspector.store.entity.SessionEntity;
import inspector.store.entity.ShellEvidenceEntity;
import inspector.store.entity.StepEntity;
import inspector.store.entity.StepId;
import inspector.store.entity.StreamId;
import inspector.store.entity.ToolCallEntity;
import inspector.store.entity.ToolCallId;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The write path of the index. One stream = one transaction: delete the stream's rows, then
 * persist them fresh. Re-indexing the same (sessionId, sourceFile) is idempotent —
 * delete-then-insert replaces what was there, across all tables, without upserts.
 *
 * <p>Every path goes through {@link PathHints#hint} before anything reaches disk, so a raw
 * absolute path can never end up in the database (DESIGN.md §4.1, §6). occurred_at is the
 * finding's own event time, persisted verbatim — nothing in this class interpolates a time.
 *
 * <p>The database is a derived cache of the corpus, not a system of record: everything in it is
 * reproducible by re-reading the logs, so a schema change is an invalidation event, not a
 * migration. {@link #resetIfStale} drops and re-creates the whole schema when the stored
 * {@code meta.schema_version} no longer matches — dropping, not emptying, because a table that
 * already exists is never redefined by the DDL — and that is the whole reason DESIGN.md §4.2 can
 * ship without a migration engine.
 *
 * <p>Rows are persisted through the {@link EntityManager} with JDBC batching. Every table except
 * {@code finding} has a natural key, so Hibernate never needs a generated id to batch them; the
 * persistence context is flushed and cleared per stream, so a run holds one stream's entities at
 * a time.
 */
@Component
public class IndexWriter {

    private static final Logger LOG = LoggerFactory.getLogger(IndexWriter.class);

    /**
     * The index schema the running build writes. 4: provider, role, finding.detail, the day
     * columns, and tool_call keyed by its event seq. A version bump is only worth making if the
     * reset it triggers can deliver the change — here, new columns and a new primary key, neither
     * of which a table that already exists would ever receive from {@code CREATE TABLE IF NOT EXISTS}.
     */
    public static final String SCHEMA_VERSION = "4";

    /** What one write produced. The orchestrator sums these into its summary line. */
    public record Written(int steps, int toolCalls, int findings, int evidenceRows) {
    }

    /**
     * What one stream is: the same {@code (sessionId, sourceFile)} pair {@link #writeStream}
     * deletes by, and the pair the whole schema is keyed on.
     */
    public record StreamKey(String sessionId, String sourceFile) {
    }

    /** The tables {@code schema.sql} owns, child-to-parent, which is the order a drop has to take. */
    private static final List<String> TABLES_CHILD_FIRST =
            List.of("shell_evidence", "finding", "tool_call", "step", "session", "meta");

    /** The DDL, applied by {@link #applySchema} — never by spring.sql.init (see {@code SchemaGate}). */
    static final String SCHEMA_DDL_CLASSPATH = "schema.sql";

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final SessionRepository sessions;
    private final StepRepository steps;
    private final ToolCallRepository toolCalls;
    private final FindingRepository findings;
    private final ShellEvidenceRepository evidence;
    private final MetaRepository meta;

    public IndexWriter(final EntityManager em, final PlatformTransactionManager transactions,
                       final SessionRepository sessions, final StepRepository steps,
                       final ToolCallRepository toolCalls, final FindingRepository findings,
                       final ShellEvidenceRepository evidence, final MetaRepository meta) {
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
        this.sessions = sessions;
        this.steps = steps;
        this.toolCalls = toolCalls;
        this.findings = findings;
        this.evidence = evidence;
        this.meta = meta;
    }

    public Written writeStream(final SessionSource source, final StreamFacts facts,
                               final List<Finding> produced, final String harnessVersion,
                               final boolean storeEvidence, final long indexedAt) {
        return tx.execute(status -> {
            final Written written = write(facts, produced, harnessVersion, storeEvidence, indexedAt);
            em.flush();
            em.clear();
            return written;
        });
    }

    private Written write(final StreamFacts facts, final List<Finding> produced,
                          final String harnessVersion, final boolean storeEvidence, final long indexedAt) {
        // The key comes from the facts the ingestor resolved: it is the same (sessionId,
        // sourceFile) the scanner produced, and it is what a re-index deletes by.
        final SessionRecord session = facts.session();
        final String sessionId = session.id();
        final String sourceFile = session.sourceFile();
        final String cwd = session.cwd();

        deleteStream(sessionId, sourceFile);

        em.persist(new SessionEntity(new StreamId(sessionId, sourceFile), session.projectSlug(),
                session.schema(), session.startedAt(), session.endedAt(), session.agentPreset(),
                session.delegationDepth(), session.model(), session.provider(), session.contextWindow(),
                harnessVersion, true, indexedAt, session.fatalTurns()));

        for (final StepRecord step : facts.steps()) {
            em.persist(new StepEntity(new StepId(sessionId, sourceFile, step.turn(), step.step()),
                    step.startedAt(), step.endedAt(), step.inputTokens(), step.outputTokens(),
                    step.decodeTps(), step.ttftMs(), step.timingSource()));
        }

        final Set<Integer> seqs = new HashSet<>();
        int calls = 0;
        for (final ToolCallRecord call : facts.toolCalls()) {
            // One row per event seq. Unique by construction (ToolCallId says why); a duplicate
            // would mean a malformed log, and the first observation of a seq is the one kept.
            if (!seqs.add(call.seq())) {
                continue;
            }
            em.persist(new ToolCallEntity(new ToolCallId(sessionId, sourceFile, call.seq()),
                    call.turn(), call.step(), call.name(), call.startedAt(), call.endedAt(),
                    call.durationMs(), call.errorCode(),
                    call.errorCode() == null ? null : ErrorPlanes.ofToolCode(call.errorCode()).name(),
                    PathHints.hint(call.absolutePath(), cwd), call.outcomeOnly(),
                    call.startedAt() == null ? null : day(call.startedAt())));
            calls++;
        }
        // The children's parent must exist before the first finding's identity insert runs.
        em.flush();

        int evidenceRows = 0;
        for (final Finding finding : produced) {
            final FindingEntity row = new FindingEntity(sessionId, sourceFile, finding.detector(),
                    finding.plane().name(), finding.category() == null ? null : finding.category().name(),
                    finding.code(), finding.detail(), finding.confidence(),
                    PathHints.hint(finding.absolutePath(), cwd), finding.seq(), finding.staleSeq(),
                    finding.causeSeq(), finding.occurredAt(), day(finding.occurredAt()), finding.summary());
            em.persist(row);
            if (storeEvidence) {
                for (final ShellEvidence shell : finding.evidence()) {
                    em.persist(new ShellEvidenceEntity(new EvidenceId(row.getId(), shell.seq()),
                            shell.verbClass().name(), PathHints.hint(evidencePath(finding, shell), cwd),
                            shell.excerpt().value()));
                    evidenceRows++;
                }
            }
        }
        return new Written(facts.steps().size(), calls, produced.size(), evidenceRows);
    }

    /** Child-to-parent: with {@code foreign_keys=on} a parent row cannot go first. */
    private void deleteStream(final String sessionId, final String sourceFile) {
        evidence.deleteStream(sessionId, sourceFile);
        findings.deleteStream(sessionId, sourceFile);
        toolCalls.deleteStream(sessionId, sourceFile);
        steps.deleteStream(sessionId, sourceFile);
        sessions.deleteStream(new StreamId(sessionId, sourceFile));
    }

    /**
     * Discards every stream of <em>this</em> corpus that the run did not write, so an index run
     * makes the database equal to the corpus instead of merging into it: a session removed from
     * the corpus has to disappear from the dashboard too.
     *
     * <p>Nothing is deleted when the index was seeded from a different corpus. Those rows are not
     * this run's corpus, and a run that never read them cannot judge that they vanished —
     * switching corpora is {@link #resetIfCorpusChanged}'s job. Silently deleting them here would
     * turn a stray {@code --inspector.corpus} typo into the loss of an index nobody meant to touch.
     *
     * @return how many streams were discarded; zero when the index already matches the corpus
     */
    public int pruneToWritten(final Path corpus, final Collection<StreamKey> written) {
        return tx.execute(status -> {
            final String stored = metaValue(MetaEntity.CORPUS);
            if (stored == null || !normalised(corpus).equals(stored)) {
                return 0;
            }
            final Set<StreamKey> keep = Set.copyOf(written);
            final List<StreamId> vanished = sessions.allStreams().stream()
                    .filter(id -> !keep.contains(new StreamKey(id.sessionId(), id.sourceFile())))
                    .toList();
            vanished.forEach(id -> deleteStream(id.sessionId(), id.sourceFile()));
            // counts only: a source_file is a path inside the corpus
            if (!vanished.isEmpty()) {
                LOG.info("the corpus no longer holds {} streams this index still described;"
                        + " pruned them", vanished.size());
            }
            return vanished.size();
        });
    }

    public void seedMeta(final String schemaVersion) {
        tx.executeWithoutResult(status -> putMeta(MetaEntity.SCHEMA_VERSION, schemaVersion));
    }

    /**
     * Records which corpus this index was built from. Never logged and never served: the path
     * carries the username, so it stays inside the gitignored database file.
     */
    public void seedCorpus(final String absoluteCorpusPath) {
        tx.executeWithoutResult(status -> putMeta(MetaEntity.CORPUS, absoluteCorpusPath));
    }

    /** Records which harness timeline the versions in this index were attributed from. */
    public void seedHarnessTimeline(final String fingerprint) {
        tx.executeWithoutResult(status -> putMeta(MetaEntity.HARNESS_TIMELINE, fingerprint));
    }

    /** Records which analysis rules — ingest and detectors — produced the rows. */
    public void seedAnalysisVersion(final String version) {
        tx.executeWithoutResult(status -> putMeta(MetaEntity.ANALYSIS_VERSION, version));
    }

    /** The analysis rules the index was built with; null for an index written before they were recorded. */
    public String storedAnalysisVersion() {
        return tx.execute(status -> metaValue(MetaEntity.ANALYSIS_VERSION));
    }

    /** The timeline the index was built with; null for an index no rev-5 run has written. */
    public String storedHarnessTimeline() {
        return tx.execute(status -> metaValue(MetaEntity.HARNESS_TIMELINE));
    }

    /**
     * Empties the index when it was built from a different corpus than the one now configured. A
     * database with sessions but no recorded corpus also resets: an index of unknown provenance
     * cannot be trusted to describe the configured corpus, and re-indexing costs seconds.
     *
     * <p>Its pair is {@link #resetIfStale}, and the two fall differently on purpose: this one
     * empties the tables and leaves the schema standing — the shape is right, only the rows
     * describe the wrong corpus — while a stale schema version drops the tables and re-applies the
     * DDL.
     *
     * @return how many sessions were discarded; zero when the index already matches
     */
    public int resetIfCorpusChanged(final Path corpus) {
        return tx.execute(status -> {
            final String stored = metaValue(MetaEntity.CORPUS);
            if (normalised(corpus).equals(stored)) {
                return 0;
            }
            final long discarded = sessions.countDistinctSessions();
            if (discarded == 0 && stored == null) {
                // nothing in it to invalidate; the corpus is recorded by the run that fills it
                return 0;
            }
            final long streams = sessions.count();
            wipe();
            LOG.info("the index was built from a different corpus than the one configured;"
                    + " reset it, discarding {} streams and {} sessions", streams, discarded);
            return (int) discarded;
        });
    }

    /**
     * The invalidation behind the no-migration-engine decision (DESIGN.md §4.2), and the step that
     * makes the schema exist at all on a fresh file. When the stored schema version does not match
     * the one this build writes — including "no value stored" and "no meta table" — every table is
     * dropped and the DDL re-applied, in one transaction, so a crash mid-reset cannot leave a
     * half-emptied database that looks like a valid empty index. When it matches, the DDL is still
     * applied: it is idempotent, and it is how a retired index reaches an existing file. Only a
     * count is logged: never a corpus path or content.
     *
     * @return how many streams were discarded; zero when the stored version already matches
     */
    public int resetIfStale(final String expectedSchemaVersion) {
        return tx.execute(status -> {
            final String stored = tableExists("meta") ? metaValue(MetaEntity.SCHEMA_VERSION) : null;
            if (expectedSchemaVersion.equals(stored)) {
                applySchema();
                return 0;
            }
            final int discarded = tableExists("session") ? countRows("session") : 0;
            TABLES_CHILD_FIRST.forEach(table ->
                    em.createNativeQuery("drop table if exists " + table).executeUpdate());
            applySchema();
            // The meta row read above is still in the persistence context, and its table is gone:
            // an update against it would touch no row. The tables are new; so is the context.
            em.clear();
            putMeta(MetaEntity.SCHEMA_VERSION, expectedSchemaVersion);
            if (stored != null || discarded > 0) {
                LOG.info("index schema version is stale (stored: {}, expected: {}); dropped and"
                        + " re-created the schema, discarding {} streams", stored, expectedSchemaVersion, discarded);
            }
            return discarded;
        });
    }

    /** Zero on a fresh database. The startup rule indexes only when this returns nothing. */
    public int countSessions() {
        return (int) sessions.countDistinctSessions();
    }

    /**
     * The same classpath script as always, on this transaction's own connection — a second
     * connection from the pool would block on the write lock this transaction holds.
     */
    private void applySchema() {
        em.unwrap(Session.class).doWork(connection -> ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource(SCHEMA_DDL_CLASSPATH), StandardCharsets.UTF_8)));
    }

    /** Child-to-parent, so the order holds with {@code foreign_keys=on}. */
    private void wipe() {
        em.createQuery("delete from ShellEvidenceEntity").executeUpdate();
        em.createQuery("delete from FindingEntity").executeUpdate();
        em.createQuery("delete from ToolCallEntity").executeUpdate();
        em.createQuery("delete from StepEntity").executeUpdate();
        em.createQuery("delete from SessionEntity").executeUpdate();
    }

    private void putMeta(final String key, final String value) {
        meta.findById(key).ifPresentOrElse(row -> row.setValue(value),
                () -> em.persist(new MetaEntity(key, value)));
    }

    private String metaValue(final String key) {
        return meta.findById(key).map(MetaEntity::getValue).orElse(null);
    }

    /** SQLite's catalogue, because the question is asked before any entity can be trusted. */
    private boolean tableExists(final String table) {
        return ((Number) em.createNativeQuery(
                        "select count(*) from sqlite_master where type = 'table' and name = ?1")
                .setParameter(1, table).getSingleResult()).intValue() > 0;
    }

    private int countRows(final String table) {
        return ((Number) em.createNativeQuery("select count(*) from " + table).getSingleResult()).intValue();
    }

    private static String normalised(final Path corpus) {
        return corpus.toAbsolutePath().normalize().toString();
    }

    /** The UTC day of an epoch-millis instant, as the daily series bucket it. */
    static String day(final long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC).toString();
    }

    /**
     * The evidence row carries the referenced path the evidence matched the finding on —
     * absolutely when the command named the same file, by basename otherwise. The match is why
     * this evidence was attached, so it is the path the drawer should show.
     */
    private static String evidencePath(final Finding finding, final ShellEvidence shell) {
        final String failed = finding.absolutePath();
        if (failed != null && shell.referencedPaths().contains(failed)) {
            return failed;
        }
        if (failed != null) {
            final String base = baseName(failed);
            for (final String referenced : shell.referencedPaths()) {
                if (base.equals(baseName(referenced))) {
                    return referenced;
                }
            }
        }
        return shell.referencedPaths().stream().min(String::compareTo).orElse(null);
    }

    private static String baseName(final String path) {
        final int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
