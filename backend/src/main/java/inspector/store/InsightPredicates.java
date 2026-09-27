package inspector.store;

import inspector.query.FindingsQuery;
import inspector.query.InsightFilter;
import inspector.query.Vocabulary;
import inspector.store.entity.FindingEntity;
import inspector.store.entity.FindingEntity_;
import inspector.store.entity.SessionEntity;
import inspector.store.entity.SessionEntity_;
import inspector.store.entity.StreamId_;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * The single filter contract of DESIGN.md §7, as JPA predicates.
 *
 * <p>Every read that answers over a filtered population builds its WHERE here: the rail's facets
 * always apply to the session the row belongs to, and the time window applies to whichever time
 * the query's root carries — a finding's event time, a session's start, a call's start, a
 * step's start. Values only ever reach SQL as bound parameters; Criteria has no other way to
 * carry them, which is what the hand-built {@code ?} lists used to promise by convention.
 *
 * <p>{@code unknown} is the one value that cannot be compared with {@code =}: the vocabulary
 * folds NULL into it ({@code coalesce(col, 'unknown')}), so the filter has to match NULL or the
 * literal — otherwise the rail offers an option that guarantees an empty dashboard, which reads
 * as "nothing is wrong here", the worst answer this tool can give.
 */
public final class InsightPredicates {

    private InsightPredicates() {
    }

    /** The rail's facets, on the session a row belongs to. */
    public static List<Predicate> facets(final CriteriaBuilder cb, final Path<SessionEntity> s,
                                         final InsightFilter filter) {
        final List<Predicate> out = new ArrayList<>();
        facet(cb, s, SessionEntity_.SCHEMA, filter.schema(), out);
        facet(cb, s, SessionEntity_.MODEL, filter.model(), out);
        facet(cb, s, SessionEntity_.AGENT_PRESET, filter.preset(), out);
        facet(cb, s, SessionEntity_.HARNESS_VERSION, filter.harnessVersion(), out);
        facet(cb, s, SessionEntity_.PROVIDER, filter.provider(), out);
        facet(cb, s, SessionEntity_.ROLE, filter.role(), out);
        return out;
    }

    /** The time window, on whichever time column the query is rooted at. Inclusive at both ends. */
    public static List<Predicate> window(final CriteriaBuilder cb, final Expression<Long> time,
                                         final InsightFilter filter) {
        final List<Predicate> out = new ArrayList<>();
        if (filter.from() != null) {
            out.add(cb.greaterThanOrEqualTo(time, filter.from()));
        }
        if (filter.to() != null) {
            out.add(cb.lessThanOrEqualTo(time, filter.to()));
        }
        return out;
    }

    /** Facets plus window, for a query whose session is {@code s} and whose time is {@code time}. */
    public static List<Predicate> all(final CriteriaBuilder cb, final Path<SessionEntity> s,
                                      final Expression<Long> time, final InsightFilter filter) {
        final List<Predicate> out = new ArrayList<>(window(cb, time, filter));
        out.addAll(facets(cb, s, filter));
        return out;
    }

    /**
     * The findings page: the shared filter on the finding's event time, plus the findings-only
     * axis values. The session join is an inner join on a declared foreign key, so it never drops
     * a finding; it is there for the facets.
     */
    public static Specification<FindingEntity> findings(final FindingsQuery query) {
        return (root, criteria, cb) -> {
            final Join<FindingEntity, SessionEntity> s = root.join(FindingEntity_.SESSION);
            final List<Predicate> where = all(cb, s, root.get(FindingEntity_.OCCURRED_AT), query.filter());
            facet(cb, root, FindingEntity_.PLANE, query.plane(), where);
            facet(cb, root, FindingEntity_.DETECTOR, query.detector(), where);
            facet(cb, root, FindingEntity_.CODE, query.code(), where);
            if (query.session() != null) {
                where.add(cb.equal(root.get(FindingEntity_.SESSION_ID), query.session()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** The distinct-session count expression every "sessions" number uses. */
    public static Expression<Long> distinctSessions(final CriteriaBuilder cb, final Path<SessionEntity> s) {
        return cb.countDistinct(s.get(SessionEntity_.ID).get(StreamId_.SESSION_ID));
    }

    private static <T> void facet(final CriteriaBuilder cb, final Path<T> path,
                                  final String attribute,
                                  final String value, final List<Predicate> out) {
        if (value == null) {
            return;
        }
        final Path<String> column = path.get(attribute);
        out.add(Vocabulary.UNKNOWN.equals(value)
                ? cb.or(cb.isNull(column), cb.equal(column, value))
                : cb.equal(column, value));
    }
}
