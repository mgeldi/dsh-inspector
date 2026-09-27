package inspector.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A shell command is whatever the model typed, and sometimes that is a long payload in quotes. The
 * analyzer runs on ingest worker threads with the default stack; a pattern that recurses once per
 * character (or once per token) overflowed it on a real corpus and failed the whole index — on
 * some runs and not others. These run the analyzer on a deliberately small stack, so a regression
 * fails every time rather than when the JIT happens to allow it.
 */
final class ShellAnalyzerStackTest {

    private static final long SMALL_STACK = 256 * 1024;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ShellAnalyzer analyzer = new ShellAnalyzer(mapper);

    @Test
    @Timeout(10)
    void aLongQuotedPayloadDoesNotOverflowAWorkerStack() throws Exception {
        final String payload = "x".repeat(200_000) + "\\\"" + "y".repeat(50_000);
        final ShellEvidence evidence = onSmallStack(
                "python3 -c \"" + payload + "\" > out/result.txt && sed -i 's/a/b/' src/app.py");

        assertThat(evidence.writeKind()).isEqualTo(WriteKind.IN_PLACE);
        assertThat(evidence.writeTargets()).containsExactlyInAnyOrder("out/result.txt", "src/app.py");
    }

    @Test
    @Timeout(10)
    void manyTokensAndManySlashesDoNotOverflowEither() throws Exception {
        final String words = "arg ".repeat(40_000);
        final String slashes = "a/".repeat(40_000) + "b.txt";
        final ShellEvidence evidence = onSmallStack("sed " + words + "-i s/a/b/ src/app.py; echo " + slashes);

        assertThat(evidence.verbClass()).isNotNull();
    }

    /**
     * Payloads are not only deep but long: a path pattern that backtracked quadratically on one long
     * token, and cubically when it held ~ or +, stalled a worker for tens of seconds instead of
     * overflowing it. Each of these is one command.
     */
    @Test
    @Timeout(10)
    void longTokensAreScannedInLinearTime() throws Exception {
        final Random random = new Random(11);
        final char[] base64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        final StringBuilder blob = new StringBuilder();
        for (int i = 0; i < 1_000_000; i++) {
            blob.append(base64[random.nextInt(base64.length)]);
        }
        for (final String command : new String[] {
                "echo " + "a".repeat(40_000),
                "echo " + "a~".repeat(20_000),
                "echo " + "a+".repeat(20_000) + ".x",
                "echo '" + blob + "' | base64 -d > out/blob.bin",
                "python3 -c \"" + "x.y+z~w ".repeat(100_000) + "\""}) {
            assertThat(onSmallStack(command)).isNotNull();
        }
        assertThat(onSmallStack("echo '" + blob + "' | base64 -d > out/blob.bin").writeTargets())
                .containsExactly("out/blob.bin");
    }

    /** The bounds change nothing a real path needs: nested directories, dots, ~ and + all still match. */
    @Test
    void realPathsAreStillFound() throws Exception {
        assertThat(onSmallStack("cat src/main/java/a/b/c/App.java ~/notes/todo.md lib/c++/x+y.hpp ./run.sh")
                .referencedPaths()).contains("src/main/java/a/b/c/App.java", "~/notes/todo.md", "lib/c++/x+y.hpp", "./run.sh");
    }

    /** The scanner blanks exactly what the pattern it replaced blanked, on inputs the pattern can take. */
    @Test
    void theQuoteScannerAgreesWithThePatternItReplaced() {
        final Pattern quoted = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'", Pattern.DOTALL);
        final Random random = new Random(7);
        final char[] alphabet = {'"', '\'', '\\', 'a', ' ', '\n', '>'};
        for (int n = 0; n < 5_000; n++) {
            final StringBuilder text = new StringBuilder();
            for (int i = random.nextInt(24); i > 0; i--) {
                text.append(alphabet[random.nextInt(alphabet.length)]);
            }
            assertThat(ShellAnalyzer.blankQuoted(text)).as("input %s", text)
                    .isEqualTo(quoted.matcher(text).replaceAll("''"));
        }
    }

    private ShellEvidence onSmallStack(final String command) throws Exception {
        final String json = mapper.writeValueAsString(Map.of("command", command));
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread thread = new Thread(null, () -> {
            try {
                result.set(analyzer.analyze(json, 1).orElseThrow());
            } catch (final Throwable t) {
                result.set(t);
            }
        }, "small-stack", SMALL_STACK);
        thread.start();
        thread.join();
        assertThat(result.get()).as("no StackOverflowError on a %d KB stack", SMALL_STACK / 1024)
                .isInstanceOf(ShellEvidence.class);
        return (ShellEvidence) result.get();
    }
}
