package inspector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The run's configuration, and the compact constructor is its validation.
 *
 * <p>Not Bean Validation, and the reason is not that the validator is missing — springdoc pulls
 * {@code jakarta.validation} and Hibernate Validator onto the classpath, so {@code @Validated}
 * with {@code @NotBlank} would now work. It is that the two validate different things.
 * {@code @Validated} fires when the binder builds this record; the compact constructor fires on
 * every construction, including the seven places across the tests that build one directly, and a
 * bad corpus path is just as wrong there. One check, one place, no dependency between the rule
 * and whoever happened to ask for the object.
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
