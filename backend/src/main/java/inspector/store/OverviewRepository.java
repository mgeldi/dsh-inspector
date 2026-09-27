package inspector.store;

import inspector.query.InsightFilter;
import inspector.store.entity.FindingEntity;
import inspector.store.entity.FindingEntity_;
import inspector.store.entity.SessionEntity;
import inspector.store.entity.SessionEntity_;
import inspector.store.entity.StepEntity;
import inspector.store.entity.StepEntity_;
import inspector.store.entity.ToolCallEntity;
import inspector.store.entity.ToolCallEntity_;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * The dashboard tile board queries (DESIGN.md §7). All read-only, all over the one filter
 * contract in {@link InsightPredicates}, with the time window rooted at the table each tile
 * measures.
 *
 * <p>Step throughput is grouped by schema and timing source, and the medians are picked from the
 * ordered rows here, never averaged from aggregates: an empty bucket reads null, it does not
 * divide by zero.
 *
 * <p>What crosses this boundary is a row of the index — {@link PlaneMixRow},
 * {@link DetectorCountRow}, {@link SeriesPointRow}, {@link ThroughputBucket} — never a wire
 * record. What the numbers are <i>called</i> on the way out is the service's business.
 */
@Repository
public class OverviewRepository {

    private final EntityManager em;

    public OverviewRepository(final EntityManager em) {
        this.em = em;
    }

    public record PlaneMixRow(String plane, long count) {
    }

    /** One error code and how many findings carry it. */
    public record CodeCountRow(String code, long count) {
    }

    public record DetectorCountRow(String detector, long count) {
    }

    /** One (detector, category, code, detail) combination and how many findings share it. */
    public record BreakdownRow(String detector, String plane, String category, String code, String detail,
                               long count) {
    }

    /** One daily bucket: the UTC day and a row count. */
    public record SeriesPointRow(String day, long count) {
    }

    /**
     * One (schema, timing source) bucket of step throughput. Either median is null when the
     * bucket holds no measured value; that null is the answer "not observed", and it must not
     * become 0 on the way to the wire.
     */
    public record ThroughputBucket(
            String schema, String timingSource, long steps, Double medianDecodeTps, Double medianTtftMs) {
    }

    /**
     * Distinct ids, not rows: the session table is keyed by (id, sourceFile) because a session
     * can hold both log conventions, so counting rows would report 168 streams as 168 "sessions"
     * next to a rail that lists 165.
     */
    public long sessionCount(final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Long> q = cb.createQuery(Long.class);
        final Root<SessionEntity> s = q.from(SessionEntity.class);
        q.select(InsightPredicates.distinctSessions(cb, s))
                .where(and(cb, InsightPredicates.all(cb, s, s.get(SessionEntity_.STARTED_AT), filter)));
        return em.createQuery(q).getSingleResult();
    }

    public long findingCount(final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Long> q = cb.createQuery(Long.class);
        final Root<FindingEntity> f = q.from(FindingEntity.class);
        q.select(cb.count(f)).where(and(cb, findingWhere(cb, f, filter)));
        return em.createQuery(q).getSingleResult();
    }

    /**
     * Observed tool calls only. A {@code tool/result} whose {@code tool/call} never appeared is
     * stored ({@code outcome_only}) so no outcome is lost, but it is not a call — counting it
     * would inflate every rate this tile feeds, differently per convention.
     */
    public long toolCallCount(final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Long> q = cb.createQuery(Long.class);
        final Root<ToolCallEntity> t = q.from(ToolCallEntity.class);
        q.select(cb.count(t)).where(and(cb, observedCallWhere(cb, t, filter)));
        return em.createQuery(q).getSingleResult();
    }

    public long stepCount(final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Long> q = cb.createQuery(Long.class);
        final Root<StepEntity> st = q.from(StepEntity.class);
        final Join<StepEntity, SessionEntity> s = st.join(StepEntity_.SESSION);
        q.select(cb.count(st)).where(and(cb, InsightPredicates.all(cb, s, st.get(StepEntity_.STARTED_AT), filter)));
        return em.createQuery(q).getSingleResult();
    }

    public List<PlaneMixRow> planeMix(final InsightFilter filter) {
        return groupCount(filter, FindingEntity_.PLANE, false).stream()
                .map(t -> new PlaneMixRow(t.get(0, String.class), t.get(1, Long.class)))
                .toList();
    }

    /** Top detectors, count descending, id ascending to break ties. */
    public List<DetectorCountRow> topDetectors(final InsightFilter filter) {
        return groupCount(filter, FindingEntity_.DETECTOR, true).stream()
                .map(t -> new DetectorCountRow(t.get(0, String.class), t.get(1, Long.class)))
                .toList();
    }

    /**
     * Top error codes, count descending, code ascending to break ties. Codeless findings — a
     * shell edit is not an error, and nothing returned a code for it — are not a bar here; they
     * are counted by {@link #uncodedCount}, so the panel still sums to the findings tile without
     * inventing an "unknown" code the harness never emitted.
     */
    public List<CodeCountRow> topCodes(final InsightFilter filter, final int limit) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<FindingEntity> f = q.from(FindingEntity.class);
        final Path<String> code = f.get(FindingEntity_.CODE);
        final Expression<Long> n = cb.count(f);
        final List<Predicate> where = findingWhere(cb, f, filter);
        where.add(cb.isNotNull(code));
        q.multiselect(code, n).where(and(cb, where)).groupBy(code).orderBy(cb.desc(n), cb.asc(code));
        return em.createQuery(q).setMaxResults(limit).getResultList().stream()
                .map(t -> new CodeCountRow(t.get(0, String.class), t.get(1, Long.class)))
                .toList();
    }

    /** Findings that carry no error code at all. */
    public long uncodedCount(final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Long> q = cb.createQuery(Long.class);
        final Root<FindingEntity> f = q.from(FindingEntity.class);
        final List<Predicate> where = findingWhere(cb, f, filter);
        where.add(cb.isNull(f.get(FindingEntity_.CODE)));
        q.select(cb.count(f)).where(and(cb, where));
        return em.createQuery(q).getSingleResult();
    }

    /**
     * Findings by what went wrong, at the finest grain the detectors produce: which rule fired,
     * what it concluded, the harness's code and the specific reason under it. Busiest first. This
     * is the table a lesson is read from — "47 of the misses followed the model's own edit" is a
     * row here, not a number anyone has to derive.
     */
    public List<BreakdownRow> breakdown(final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<FindingEntity> f = q.from(FindingEntity.class);
        final Path<String> detector = f.get(FindingEntity_.DETECTOR);
        final Path<String> plane = f.get(FindingEntity_.PLANE);
        final Path<String> category = f.get(FindingEntity_.CATEGORY);
        final Path<String> code = f.get(FindingEntity_.CODE);
        final Path<String> detail = f.get(FindingEntity_.DETAIL);
        final Expression<Long> n = cb.count(f);
        q.multiselect(detector, plane, category, code, detail, n).where(and(cb, findingWhere(cb, f, filter)))
                .groupBy(detector, plane, category, code, detail)
                .orderBy(cb.desc(n), cb.asc(detector), cb.asc(category), cb.asc(code), cb.asc(detail));
        return em.createQuery(q).getResultList().stream()
                .map(t -> new BreakdownRow(t.get(0, String.class), t.get(1, String.class), t.get(2, String.class),
                        t.get(3, String.class), t.get(4, String.class), t.get(5, Long.class)))
                .toList();
    }

    /** Findings per UTC day, the first of the two chart series. */
    public List<SeriesPointRow> findingSeries(final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<FindingEntity> f = q.from(FindingEntity.class);
        final Path<String> day = f.get(FindingEntity_.DAY);
        q.multiselect(day, cb.count(f)).where(and(cb, findingWhere(cb, f, filter)))
                .groupBy(day).orderBy(cb.asc(day));
        return series(q);
    }

    /**
     * Observed tool calls per UTC day, the second series — on the same basis as the tile. A call
     * with no start has no day and cannot be placed on the axis.
     */
    public List<SeriesPointRow> toolCallSeries(final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<ToolCallEntity> t = q.from(ToolCallEntity.class);
        final Path<String> day = t.get(ToolCallEntity_.DAY);
        final List<Predicate> where = observedCallWhere(cb, t, filter);
        where.add(cb.isNotNull(day));
        q.multiselect(day, cb.count(t)).where(and(cb, where)).groupBy(day).orderBy(cb.asc(day));
        return series(q);
    }

    /**
     * Step throughput per (schema, timing source) bucket. The medians are picked from the
     * ordered step values (the middle one; the mean of the two middles for even counts); a
     * bucket with no measured value reads null.
     */
    public List<ThroughputBucket> throughput(final InsightFilter filter) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<StepEntity> st = q.from(StepEntity.class);
        final Join<StepEntity, SessionEntity> s = st.join(StepEntity_.SESSION);
        q.multiselect(s.get(SessionEntity_.SCHEMA), st.get(StepEntity_.TIMING_SOURCE),
                        st.get(StepEntity_.DECODE_TPS), st.get(StepEntity_.TTFT_MS))
                .where(and(cb, InsightPredicates.all(cb, s, st.get(StepEntity_.STARTED_AT), filter)));

        final Map<String, List<StepSample>> groups = new LinkedHashMap<>();
        for (final Tuple row : em.createQuery(q).getResultList()) {
            final StepSample sample = new StepSample(row.get(0, String.class), row.get(1, String.class),
                    row.get(2, Double.class),
                    row.get(3, Integer.class) == null ? null : row.get(3, Integer.class).doubleValue());
            groups.computeIfAbsent(sample.schema() + '\u0000' + sample.source(), key -> new ArrayList<>())
                    .add(sample);
        }
        final List<ThroughputBucket> rows = new ArrayList<>();
        groups.values().forEach(samples -> rows.add(new ThroughputBucket(
                samples.get(0).schema(),
                samples.get(0).source(),
                samples.size(),
                median(samples.stream().map(StepSample::tps).filter(Objects::nonNull).toList()),
                median(samples.stream().map(StepSample::ttft).filter(Objects::nonNull).toList()))));
        rows.sort(Comparator.comparing(ThroughputBucket::schema)
                .thenComparing(ThroughputBucket::timingSource));
        return rows;
    }

    /** One step's measured throughput values; either may be null. */
    private record StepSample(String schema, String source, Double tps, Double ttft) {
    }

    /** Median of the values, or null for an empty input — never a division by zero. */
    static Double median(final List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        final List<Double> sorted = values.stream().sorted().toList();
        final int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    private List<Tuple> groupCount(final InsightFilter filter,
                                   final String key,
                                   final boolean byCountDesc) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> q = cb.createTupleQuery();
        final Root<FindingEntity> f = q.from(FindingEntity.class);
        final Path<String> column = f.get(key);
        final Expression<Long> n = cb.count(f);
        q.multiselect(column, n).where(and(cb, findingWhere(cb, f, filter))).groupBy(column)
                .orderBy(byCountDesc ? List.of(cb.desc(n), cb.asc(column)) : List.of(cb.asc(column)));
        return em.createQuery(q).getResultList();
    }

    private List<SeriesPointRow> series(final CriteriaQuery<Tuple> q) {
        return em.createQuery(q).getResultList().stream()
                .map(t -> new SeriesPointRow(t.get(0, String.class), t.get(1, Long.class)))
                .toList();
    }

    static List<Predicate> findingWhere(final CriteriaBuilder cb, final Root<FindingEntity> f,
                                        final InsightFilter filter) {
        final Join<FindingEntity, SessionEntity> s = f.join(FindingEntity_.SESSION);
        return InsightPredicates.all(cb, s, f.get(FindingEntity_.OCCURRED_AT), filter);
    }

    static List<Predicate> observedCallWhere(final CriteriaBuilder cb, final Root<ToolCallEntity> t,
                                             final InsightFilter filter) {
        final Join<ToolCallEntity, SessionEntity> s = t.join(ToolCallEntity_.SESSION);
        final List<Predicate> where = InsightPredicates.all(cb, s, t.get(ToolCallEntity_.STARTED_AT), filter);
        where.add(cb.isFalse(t.get(ToolCallEntity_.OUTCOME_ONLY)));
        return where;
    }

    static Predicate and(final CriteriaBuilder cb, final List<Predicate> predicates) {
        return cb.and(predicates.toArray(Predicate[]::new));
    }
}
