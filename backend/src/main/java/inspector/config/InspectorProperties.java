package inspector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The run's configuration, and the compact constructor is its validation.
 *
 * <p>Not Bean Validation. {@code @Validated} plus {@code @NotBlank} would need
 * {@code spring-boot-starter-validation}, which is not on this classpath — measured, not
 * assumed: {@code mvn dependency:build-classpath} lists no {@code jakarta.validation} entry. A
 * dependency whose entire job is one blank check is the trade DESIGN.md §4.2 refuses to make for
 * Flyway. The constructor check fails at the same moment — during binding, before anything
 * touches a directory — and Boot reports either kind as a binding failure naming the property.
 */
@ConfigurationProperties(prefix = "inspector")
public record InspectorProperties(
        @DefaultValue("fixtures/sessions") String corpus,
        @DefaultValue("unknown") String harnessVersion,
        @DefaultValue Evidence evidence) {

    /**
     * A blank corpus is refused rather than honoured, because a blank path is not "nothing
     * configured": {@code Path.of("")} is the JVM's working directory, so
     * {@code --inspector.corpus=} pointed the scanner at wherever the jar happened to be started
     * from and it would have indexed whatever it found there — the repository root, in the usual
     * case, which contains a {@code corpus/} full of real session logs.
     */
    public InspectorProperties {
        if (corpus == null || corpus.isBlank()) {
            throw new IllegalStateException("inspector.corpus must name a directory of session"
                    + " logs; a blank value would resolve to the working directory and scan that");
        }
    }

    public record Evidence(@DefaultValue("true") boolean store) {
    }
}
