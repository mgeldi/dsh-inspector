package inspector.store;

import inspector.query.InsightFilter;
import inspector.query.Vocabulary;
import inspector.store.entity.FindingEntity;
import inspector.store.entity.FindingEntity_;
import inspector.store.entity.SessionEntity;
import inspector.store.entity.SessionEntity_;
import inspector.store.entity.ToolCallEntity;
import inspector.store.entity.ToolCallEntity_;
import inspector.store.entity.ToolCallId_;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Repository;

/**
 * The cohorts comparison queries (DESIGN.md §7): raw counters per cohort key, no rates. Rates
 * are computed by the service, which also knows which row is the baseline.
 *
 * <p>Denominators are <i>observed</i> tool calls ({@code outcome_only = 0}): a
 * {@code tool/result} whose {@code tool/call} never appeared is stored so no outcome is lost, but
 * it is not a call and must not sit in a denominator.
 *
 * <p>The axis is a session attribute the service chose from a whitelist; nothing user-supplied
 * becomes a path. NULL axis values fold into {@code unknown}, the same bucket the vocabulary
 * offers.
 */
@Repository
public class CohortRepository {

    private final EntityManager em;

    public CohortRepository(final EntityManager em) {
        this.em = em;
    }

    /** One cohort's raw counters, split by plane. */
    public record Cohort(String key, long sessions, long toolCalls, long findings,
                         long guardFindings, long misuseFindings, long infraFindings) {
    }

    /** The cohort table for one axis, plus whether every version in it is inferred. */
    public record Result(List<Cohort> cohorts, boolean allVersionInferred) {
    }

    /** One (cohort, code) count, the judge's unit (codeless findings are keyed by detector). */
    public record CodeCount(String key, String code, long count) {
    }

    /** Observed calls of one session inside one cohort. */
    public record SessionCalls(String key, String sessionId, long calls) {
    }

    /** Findings of one session inside one cohort, by plane and by code (or detector, for a codeless one). */
    public record SessionFindings(String key, String sessionId, String plane, String code, long count) {
    }

    /**
     * Observed calls per (cohort, session) — the exposure the judge's dispersion estimate needs, so
     * it can tell failures spread across many conversations from one conversation's burst.
     */
    public List<SessionCalls> callsPerSession(final String axis, final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<ToolCallEntity> t = q.from(ToolCallEntity.class);
        final Join<ToolCallEntity, SessionEntity> s = t.join(ToolCallEntity_.SESSION);
        final Expression<String> key = key(cb, s, axis);
        final Path<String> session = t.get(ToolCallEntity_.ID).get(ToolCallId_.SESSION_ID);
        final var where = InsightPredicates.all(cb, s, t.get(ToolCallEntity_.STARTED_AT), filter);
        where.add(cb.isFalse(t.get(ToolCallEntity_.OUTCOME_ONLY)));
        q.multiselect(key, session, cb.count(t)).where(OverviewRepository.and(cb, where)).groupBy(key, session);
        return em.createQuery(q).getResultList().stream()
                .map(r -> new SessionCalls(r.get(0, String.class), r.get(1, String.class), r.get(2, Long.class)))
                .toList();
    }

    /** Findings per (cohort, session, plane, code) — the numerators of the same dispersion estimate. */
    public List<SessionFindings> findingsPerSession(final String axis, final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<FindingEntity> f = q.from(FindingEntity.class);
        final Join<FindingEntity, SessionEntity> s = f.join(FindingEntity_.SESSION);
        final Expression<String> key = key(cb, s, axis);
        final Path<String> session = f.get(FindingEntity_.SESSION_ID);
        final Path<String> plane = f.get(FindingEntity_.PLANE);
        final Expression<String> code = cb.coalesce(f.get(FindingEntity_.CODE), f.get(FindingEntity_.DETECTOR));
        q.multiselect(key, session, plane, code, cb.count(f))
                .where(OverviewRepository.and(cb, InsightPredicates.all(cb, s, f.get(FindingEntity_.OCCURRED_AT), filter)))
                .groupBy(key, session, plane, code);
        return em.createQuery(q).getResultList().stream()
                .map(r -> new SessionFindings(r.get(0, String.class), r.get(1, String.class), r.get(2, String.class),
                        r.get(3, String.class), r.get(4, Long.class)))
                .toList();
    }

    /**
     * @param axis   the whitelisted session attribute to group by
     * @param filter the shared filter contract of §7, applied to every aggregate in this table. A
     *               cohort rate that ignored the rail would describe a different population than
     *               the dashboard above it.
     */
    public Result cohorts(final String axis, final InsightFilter filter) {
        final Map<String, Long> sessions = sessionCounts(axis, filter);
        final Map<String, Long> toolCalls = toolCallCounts(axis, filter);
        final Map<String, Map<String, Long>> byPlane = findingCountsByPlane(axis, filter);

        final TreeSet<String> keys = new TreeSet<>(sessions.keySet());
        keys.addAll(toolCalls.keySet());
        keys.addAll(byPlane.keySet());

        final List<Cohort> cohorts = new ArrayList<>();
        for (final String key : keys) {
            final Map<String, Long> planes = byPlane.getOrDefault(key, Map.of());
            final long guard = planes.getOrDefault("GUARD", 0L);
            final long misuse = planes.getOrDefault("MODEL_MISUSE", 0L);
            final long infra = planes.getOrDefault("INFRASTRUCTURE", 0L);
            cohorts.add(new Cohort(key, sessions.getOrDefault(key, 0L), toolCalls.getOrDefault(key, 0L),
                    guard + misuse + infra, guard, misuse, infra));
        }
        return new Result(List.copyOf(cohorts), allVersionInferred(filter));
    }

    /**
     * Findings per (cohort, code) — the per-failure numerators the judge compares. A finding with
     * no code (a shell edit) is keyed by its detector instead, so every finding is counted once.
     */
    public List<CodeCount> codeCounts(final String axis,
                                      final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<FindingEntity> f = q.from(FindingEntity.class);
        final Join<FindingEntity, SessionEntity> s = f.join(FindingEntity_.SESSION);
        final Expression<String> key = key(cb, s, axis);
        final Expression<String> code = cb.coalesce(f.get(FindingEntity_.CODE), f.get(FindingEntity_.DETECTOR));
        q.multiselect(key, code, cb.count(f))
                .where(OverviewRepository.and(cb, InsightPredicates.all(cb, s, f.get(FindingEntity_.OCCURRED_AT), filter)))
                .groupBy(key, code);
        return em.createQuery(q).getResultList().stream()
                .map(t -> new CodeCount(t.get(0, String.class), t.get(1, String.class), t.get(2, Long.class)))
                .toList();
    }

    /**
     * Distinct ids, not rows: {@code session} is keyed (id, source file), so a session stored under
     * both conventions is two rows, and counting rows would report it twice.
     */
    private Map<String, Long> sessionCounts(final String axis,
                                            final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<SessionEntity> s = q.from(SessionEntity.class);
        final Expression<String> key = key(cb, s, axis);
        q.multiselect(key, InsightPredicates.distinctSessions(cb, s))
                .where(OverviewRepository.and(cb, InsightPredicates.all(cb, s, s.get(SessionEntity_.STARTED_AT), filter)))
                .groupBy(key);
        return toMap(em.createQuery(q).getResultList());
    }

    private Map<String, Long> toolCallCounts(final String axis,
                                             final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<ToolCallEntity> t = q.from(ToolCallEntity.class);
        final Join<ToolCallEntity, SessionEntity> s = t.join(ToolCallEntity_.SESSION);
        final Expression<String> key = key(cb, s, axis);
        final var where = InsightPredicates.all(cb, s, t.get(ToolCallEntity_.STARTED_AT), filter);
        where.add(cb.isFalse(t.get(ToolCallEntity_.OUTCOME_ONLY)));
        q.multiselect(key, cb.count(t)).where(OverviewRepository.and(cb, where)).groupBy(key);
        return toMap(em.createQuery(q).getResultList());
    }

    private Map<String, Map<String, Long>> findingCountsByPlane(
            final String axis, final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<FindingEntity> f = q.from(FindingEntity.class);
        final Join<FindingEntity, SessionEntity> s = f.join(FindingEntity_.SESSION);
        final Expression<String> key = key(cb, s, axis);
        final Path<String> plane = f.get(FindingEntity_.PLANE);
        q.multiselect(key, plane, cb.count(f))
                .where(OverviewRepository.and(cb, InsightPredicates.all(cb, s, f.get(FindingEntity_.OCCURRED_AT), filter)))
                .groupBy(key, plane);
        final Map<String, Map<String, Long>> out = new HashMap<>();
        for (final Tuple row : em.createQuery(q).getResultList()) {
            out.computeIfAbsent(row.get(0, String.class), k -> new HashMap<>())
                    .put(row.get(1, String.class), row.get(2, Long.class));
        }
        return out;
    }

    /**
     * The "is the version declared?" claim is made about the selection on screen, not the whole
     * index: filtered down to one cohort, an all-inferred index would be a sentence about data
     * nobody is looking at.
     */
    private boolean allVersionInferred(final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Long> q = cb.createQuery(Long.class);
        final Root<SessionEntity> s = q.from(SessionEntity.class);
        final var where = InsightPredicates.all(cb, s, s.get(SessionEntity_.STARTED_AT), filter);
        where.add(cb.isFalse(s.get(SessionEntity_.VERSION_INFERRED)));
        q.select(cb.count(s)).where(OverviewRepository.and(cb, where));
        return em.createQuery(q).getSingleResult() == 0;
    }

    private static Expression<String> key(final CriteriaBuilder cb, final Path<SessionEntity> s,
                                          final String axis) {
        return cb.coalesce(s.get(axis), Vocabulary.UNKNOWN);
    }

    private static Map<String, Long> toMap(final List<Tuple> rows) {
        final Map<String, Long> out = new HashMap<>();
        rows.forEach(row -> out.put(row.get(0, String.class), row.get(1, Long.class)));
        return out;
    }
}
