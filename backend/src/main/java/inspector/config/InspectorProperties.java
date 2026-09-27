package inspector.config;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The run's configuration, and the compact constructor is its validation.
 *
 * <p>Not Bean Validation, and the reason is not that the validator is missing — springdoc pulls
 * {@code jakarta.validation} and Hibernate Validator onto the classpath, so {@code @Validated}
 * with {@code @NotBlank} would now work. It is that the two validate different things.
 * {@code @Validated} fires when the binder builds this record; the compact constructor fires on
 * every construction, including the places across the tests that build one directly, and a
 * bad corpus path is just as wrong there. One check, one place, no dependency between the rule
 * and whoever happened to ask for the object.
 *
 * @param harnessVersion  the version every session gets when no timeline entry covers it
 * @param harnessTimeline which harness version was live from when. The session log does not
 *                        record it (DESIGN.md §3.4), so the operator — or the loop that changes
 *                        the harness — records it here, one entry per change. Kept in its own
 *                        file and passed with {@code --spring.config.import}, it is the only way a
 *                        cohort comparison by version can mean "before and after that change".
 */
@ConfigurationProperties(prefix = "inspector")
public record InspectorProperties(
        @DefaultValue("fixtures/sessions") String corpus,
        @DefaultValue("unknown") String harnessVersion,
        @DefaultValue Evidence evidence,
        List<HarnessRelease> harnessTimeline) {

    /**
     * A blank corpus is refused rather than honoured, because a blank path is not "nothing
     * configured": {@code Path.of("")} is the JVM's working directory, so
     * {@code --inspector.corpus=} pointed the scanner at wherever the jar happened to be started
     * from and it would have indexed whatever it found there — the repository root, in the usual
     * case, which contains a {@code corpus/} full of real session logs.
     *
     * <p>The timeline is sorted here, so every reader can take "the last entry not after the
     * session start" without re-checking an order the configuration file never promised.
     */
    public InspectorProperties {
        if (corpus == null || corpus.isBlank()) {
            throw new IllegalStateException("inspector.corpus must name a directory of session"
                    + " logs; a blank value would resolve to the working directory and scan that");
        }
        harnessTimeline = harnessTimeline == null ? List.of()
                : harnessTimeline.stream().sorted(Comparator.comparing(HarnessRelease::since)).toList();
    }

    /**
     * The version a session that started at {@code startedAt} (epoch millis) ran under: the last
     * timeline entry not after it, else {@code fallback} — which is {@link #harnessVersion} unless
     * a run was handed another one.
     */
    public String harnessVersionAt(final long startedAt, final String fallback) {
        String version = fallback;
        for (final HarnessRelease release : harnessTimeline) {
            if (release.since().toEpochMilli() > startedAt) {
                break;
            }
            version = release.version();
        }
        return version;
    }

    /**
     * What the versions in an index were attributed from: the fallback and every timeline entry.
     * Stored with the index, so a timeline edit is noticed on the next boot rather than leaving
     * every session carrying the version an older timeline gave it.
     */
    public String timelineFingerprint(final String fallback) {
        final StringBuilder out = new StringBuilder(fallback == null ? "" : fallback);
        harnessTimeline.forEach(r -> out.append(';').append(r.since()).append('=').append(r.version()));
        return out.toString();
    }

    public record Evidence(@DefaultValue("true") boolean store) {
    }

    /**
     * One harness change: from {@code since} (inclusive) until the next entry, sessions ran
     * under {@code version}. {@code since} is ISO-8601, with an offset or {@code Z}.
     */
    public record HarnessRelease(Instant since, String version) {

        public HarnessRelease {
            if (since == null) {
                throw new IllegalStateException("a harness-timeline entry needs a 'since' instant");
            }
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("the harness-timeline entry at " + since
                        + " needs a non-blank 'version'");
            }
        }
    }
}
