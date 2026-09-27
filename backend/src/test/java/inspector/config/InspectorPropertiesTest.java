package inspector.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The one property that can do damage if it is empty. A blank value is not "unset":
 * {@code Path.of("")} is the working directory, so the value that looks like a refusal to
 * configure anything is in fact a request to scan wherever the process happens to be standing —
 * which, started from the repository root, is a directory containing real session logs.
 */
final class InspectorPropertiesTest {

    @Test
    void aBlankCorpusIsRefusedRatherThanReadAsTheWorkingDirectory() {
        assertThatThrownBy(() -> new InspectorProperties("", "0.1.0", evidence(), java.util.List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inspector.corpus");
    }

    @Test
    void whitespaceIsBlankForThisPurpose() {
        assertThatThrownBy(() -> new InspectorProperties("   ", "0.1.0", evidence(), java.util.List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aConfiguredCorpusIsTakenVerbatim() {
        assertThatCode(() -> new InspectorProperties("fixtures/sessions", "0.1.0", evidence(), java.util.List.of()))
                .doesNotThrowAnyException();
    }

    private static InspectorProperties.Evidence evidence() {
        return new InspectorProperties.Evidence(true);
    }
}
