package inspector.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.luben.zstd.ZstdOutputStream;
import inspector.ingest.Convention;
import inspector.ingest.FatalTurn;
import inspector.ingest.SessionIngestor;
import inspector.ingest.SessionRecord;
import inspector.ingest.SessionSource;
import inspector.ingest.ShellAnalyzer;
import inspector.ingest.StreamFacts;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

final class FatalTurnDetectorTest {

    private static final long T0 = 1_760_000_000_000L;

    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private final FatalTurnDetector detector = new FatalTurnDetector();
    private int seq;

    @Test
    void aTurnEndingInErrorBecomesOneInfrastructureFinding() {
        final List<Finding> findings = detector.detect(facts(new FatalTurn(7, "media_budget_exceeded")));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.detector()).isEqualTo(FatalTurnDetector.ID);
            assertThat(f.plane()).isEqualTo(Plane.INFRASTRUCTURE);
            assertThat(f.category()).isNull();
            assertThat(f.code()).isEqualTo("media_budget_exceeded");
            assertThat(f.confidence()).isNull();
            assertThat(f.summary()).isEqualTo("turn 7 ended in error (media_budget_exceeded)");
        });
    }

    @Test
    void aFatalTurnWithoutAParsedCodeOmitsTheCodeFromTheSummary() {
        final List<Finding> findings = detector.detect(facts(new FatalTurn(3, null)));

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.summary()).isEqualTo("turn 3 ended in error"));
    }

    @Test
    void aUserMessageMentioningTheErrorCodeIsNotAFatalTurn() throws IOException {
        // §5.2's contamination trap: the corpus has hundreds of lines that merely mention
        // media_budget_exceeded (documentation replayed into the context), while only a
        // turn/end with reason.kind == "error" is a death. A user message that quotes the
        // code must never reach this detector as a FatalTurn.
        final StreamFacts ingested = ingest(
                sessionLine(),
                userMessageLine("we keep seeing 400: media_budget_exceeded in the docs, "
                        + "is that the vision budget?"));

        assertThat(ingested.fatalTurns()).isEmpty();
        assertThat(detector.detect(ingested)).isEmpty();
    }

    private StreamFacts facts(final FatalTurn... fatalTurns) {
        return new StreamFacts(sessionRecord(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(fatalTurns), List.of(), 0L);
    }

    private SessionRecord sessionRecord() {
        return new SessionRecord("s-demo", "session.jsonl.zstd", "demo-project", "V0",
                T0, null, null, null, null, null, 0, "/home/dev/demo");
    }

    private StreamFacts ingest(final String... lines) throws IOException {
        final Path file = temp.resolve(Convention.FILE_V0);
        try (ZstdOutputStream out = new ZstdOutputStream(Files.newOutputStream(file));
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            for (final String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
        final SessionSource source = new SessionSource(file, "s-demo", "demo-project",
                Convention.V0, Convention.FILE_V0);
        return new SessionIngestor(mapper, new ShellAnalyzer(mapper)).ingest(source);
    }

    private String sessionLine() {
        return "{\"type\":\"session\",\"seq\":" + seq++ + ",\"time\":" + T0
                + ",\"id\":\"s-demo\",\"createdAt\":" + T0
                + ",\"cwd\":\"/home/dev/demo\",\"version\":0,\"data\":{}}";
    }

    private String userMessageLine(final String text) {
        final String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"type\":\"user/message\",\"seq\":" + seq++ + ",\"time\":" + (T0 + 1000)
                + ",\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"" + escaped + "\"}]}}";
    }
}
