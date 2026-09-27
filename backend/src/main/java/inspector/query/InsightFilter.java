package inspector.query;


import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The one filter contract every GET endpoint shares (DESIGN.md §7): the
 * dashboard rail's values bound from the query string.
 *
 * <p>Each endpoint accepts a subset of the fields; an absent value means
 * "no filter", a present value must be in the index vocabulary or the
 * request fails with a 400 carrying the allowed set.
 *
 * <p>Every place that binds it carries {@code @ParameterObject} next to
 * {@code @ModelAttribute}. Without that, the generated OpenAPI document describes this type as
 * one query parameter named {@code filter} whose schema is this record — an object in a place
 * where HTTP has objects nowhere, and a document a client could follow into a request that
 * matches nothing. Spring's own version of that annotation existed in Framework 6.1 and is gone
 * from 7.0, so the one springdoc ships is what is available here.
 *
 * <p>The {@code @Schema} descriptions repeat the {@code @param} lines below, which is a second
 * copy of the same sentence and therefore a drift hazard this repository has already paid for
 * (DESIGN.md §6 enumerated two indexes the code had retired). The single-source alternative is
 * springdoc's javadoc provider, and it was tried: {@code therapi-runtime-javadoc-scribe} 0.15.0
 * produces no output under JDK 26 even with {@code -proc:full}, so the build stays green and the
 * document stays empty. Eight short lines, kept next to the sentences they copy, beat a dependency
 * that silently does nothing — revisit if the scribe ever ships a JDK 26 build.
 *
 * @param from            event-time lower bound, epoch millis, inclusive
 * @param to              event-time upper bound, epoch millis, inclusive
 * @param schema          session convention, e.g. {@code v0}/{@code v3}
 * @param model           declared model name
 * @param preset          agent preset name
 * @param harnessVersion  declared or inferred harness version
 * @param provider        the route the session's requests went to ({@code request/context.provider})
 * @param role            {@code orchestrator} (delegation depth 0) or {@code subagent} (depth ≥ 1)
 */
public record InsightFilter(
        @Schema(description = "Event-time lower bound, epoch millis, inclusive.", example = "1757894400000")
        Long from,
        @Schema(description = "Event-time upper bound, epoch millis, inclusive.", example = "1757980800000")
        Long to,
        @Schema(description = "Session log convention. Must be a value present in the index.", example = "V3")
        String schema,
        @Schema(description = "Declared model name. Must be a value present in the index.")
        String model,
        @Schema(description = "Agent preset name. Must be a value present in the index.")
        String preset,
        @Schema(description = "Declared or inferred harness version. Must be a value present in the index.")
        String harnessVersion,
        @Schema(description = "Provider route the session's requests went to. Must be a value present in the index.",
                example = "local-impl")
        String provider,
        @Schema(description = "orchestrator (delegation depth 0) or subagent (depth 1 or more).",
                example = "subagent")
        String role) {

    /** No filter at all: every value absent. */
    public static InsightFilter none() {
        return new InsightFilter(null, null, null, null, null, null, null, null);
    }

    /** True when any value would narrow the population. */
    public boolean isActive() {
        return from != null || to != null || schema != null || model != null || preset != null
                || harnessVersion != null || provider != null || role != null;
    }

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
        requireKnown(vocabulary, "provider", provider);
        requireKnown(vocabulary, "role", role);
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
