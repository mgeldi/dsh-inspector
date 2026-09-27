package inspector.query;

/**
 * What the findings page asks for: the shared rail filter plus the findings-only axis values.
 * A value type and nothing else — the store turns it into predicates (the one filter contract of
 * DESIGN.md §7, now expressed in JPA rather than in SQL fragments), and every value in it has
 * been checked against the vocabulary before it gets that far.
 */
public record FindingsQuery(InsightFilter filter, String plane, String detector, String code,
                            String session) {

    public FindingsQuery {
        filter = filter == null ? InsightFilter.none() : filter;
    }
}
