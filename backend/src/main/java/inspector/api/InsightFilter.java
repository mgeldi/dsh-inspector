package inspector.api;

import inspector.api.dto.Vocabulary;

import java.util.List;

/**
 * The one filter contract every GET endpoint shares (DESIGN.md §7): the
 * dashboard rail's six values bound from the query string.
 *
 * <p>Each endpoint accepts a subset of the fields; an absent value means
 * "no filter", a present value must be in the index vocabulary or the
 * request fails with a 400 carrying the allowed set.
 *
 * @param from            event-time lower bound, epoch millis, inclusive
 * @param to              event-time upper bound, epoch millis, inclusive
 * @param schema          session convention, e.g. {@code v0}/{@code v3}
 * @param model           declared model name
 * @param preset          agent preset name
 * @param harnessVersion  declared or inferred harness version
 */
public record InsightFilter(
        Long from,
        Long to,
        String schema,
        String model,
        String preset,
        String harnessVersion) {

    /**
     * Validate every present value against the index vocabulary.
     *
     * @throws UnknownFilterValueException if a value is not allowed
     */
    public void validate(final Vocabulary vocabulary) {
        requireKnown(vocabulary, "schema", schema);
        requireKnown(vocabulary, "model", model);
        requireKnown(vocabulary, "preset", preset);
        requireKnown(vocabulary, "harnessVersion", harnessVersion);
    }

    /**
     * The single vocabulary check all GETs run before touching SQL. Null is
     * "no filter"; a present value must be in the allowed set.
     *
     * @throws UnknownFilterValueException if the value is not allowed
     */
    public static void requireKnown(final Vocabulary vocabulary, final String field, final String value) {
        if (value == null) {
            return;
        }
        final List<String> allowed = vocabulary.allowedFor(field);
        if (!allowed.contains(value)) {
            throw new UnknownFilterValueException(field, value, allowed);
        }
    }
}
