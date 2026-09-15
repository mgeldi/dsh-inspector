package inspector.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ShellAnalyzerTest {

    private final ShellAnalyzer analyzer = new ShellAnalyzer();

    /**
     * Embeds the raw command as the value of a JSON string. Backslashes, quotes and newlines
     * must be escaped: the arguments string is JSON, and Jackson refuses raw control characters
     * inside string values.
     */
    private Optional<ShellEvidence> analyze(final String command) {
        final String escaped = command.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n");
        return analyzer.analyze("{\"command\":\"" + escaped + "\",\"description\":\"probe\"}", 10);
    }

    @Test
    void inPlaceSedIsMutating() {
        final Optional<ShellEvidence> evidence = analyze("sed -i 's/a/b/' src/main/App.java");
        assertThat(evidence).get()
                .hasFieldOrPropertyWithValue("verbClass", VerbClass.MUTATING);
        assertThat(evidence.map(ShellEvidence::referencedPaths).orElseThrow())
                .contains("src/main/App.java");
    }

    @Test
    void aHomeDirectoryContainingDevAsAPathSegmentIsStillAPath() {
        // /dev/ excludes device files only. The segment must not be matched anywhere: a
        // working directory like /home/dev/demo/app.java is a real file, and dropping it
        // would blind the absolute-path attribution that depends on it.
        final Optional<ShellEvidence> evidence =
                analyze("sed -i 's/a/b/' /home/dev/demo/App.java");
        assertThat(evidence).get()
                .hasFieldOrPropertyWithValue("verbClass", VerbClass.MUTATING);
        assertThat(evidence.map(ShellEvidence::referencedPaths).orElseThrow())
                .contains("/home/dev/demo/App.java");
    }

    @Test
    void deviceFilesAreStillExcludedFromTheReferencedPaths() {
        final Optional<ShellEvidence> evidence =
                analyze("cat /home/dev/demo/notes.txt 2>/dev/null");
        assertThat(evidence.map(ShellEvidence::referencedPaths).orElseThrow())
                .contains("/home/dev/demo/notes.txt")
                .doesNotContain("/dev/null");
    }

    @Test
    void gitRestoreIsAVcsRestoreNotAViolation() {
        assertThat(analyze("git checkout README.md")).get()
                .hasFieldOrPropertyWithValue("verbClass", VerbClass.VCS_RESTORE);
    }

    @Test
    void readingCommandsAreReadOnly() {
        for (final String cmd : new String[]{"wc -l notes.md", "ls -la out.log",
                "sed -n '10,20p' app.js", "grep -r needle src", "cat README.md"}) {
            assertThat(analyze(cmd)).get()
                    .hasFieldOrPropertyWithValue("verbClass", VerbClass.READ_ONLY);
        }
    }

    @Test
    void bareScriptExecutionIsOtherAndNeverMutating() {
        assertThat(analyze("node scripts/audit-report.mjs")).get()
                .hasFieldOrPropertyWithValue("verbClass", VerbClass.OTHER);
    }

    @Test
    void heredocPythonWriteIsMutating() {
        assertThat(analyze("python3 - <<EOF\nopen(p).write(x)\nEOF")).get()
                .hasFieldOrPropertyWithValue("verbClass", VerbClass.MUTATING);
    }

    @Test
    void outputRedirectCountsAsMutatingButErrorRedirectDoesNot() {
        assertThat(analyze("echo hi > notes.md")).get()
                .hasFieldOrPropertyWithValue("verbClass", VerbClass.MUTATING);
        assertThat(analyze("grep x notes.md 2>/dev/null")).get()
                .hasFieldOrPropertyWithValue("verbClass", VerbClass.READ_ONLY);
    }

    @Test
    void versionNumbersAreNotMistakenForPaths() {
        assertThat(analyze("node --version 1.23")
                .map(ShellEvidence::referencedPaths)
                .orElseThrow())
                .isEmpty();
    }

    @Test
    void excerptTruncatesAndMasksCredentials() {
        final ShellEvidence evidence = analyze(
                "curl -H \"Authorization: Bearer abcdef0123456789abcdef0123456789\" https://api")
                        .orElseThrow();
        assertThat(evidence.excerpt().value()).contains("[REDACTED]")
                .doesNotContain("abcdef0123456789").hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void aMalformedArgumentsStringYieldsEmpty() {
        assertThat(analyzer.analyze("{not json", 3)).isEmpty();
    }
}
