package inspector.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

final class SessionIngestorTest {

    private static final long T0 = 1_760_000_000_000L;

    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private int seq;

    @Test
    void v3StepDerivesThroughputFromEmbeddedStream() throws IOException {
        final StreamFacts facts = ingest(Convention.V3,
                stepStart(0, 0, T0),
                v3Message(0, 0, T0 + 4000, 350, List.of(chunk(T0 + 1000), chunk(T0 + 3000))));

        final StepRecord step = facts.steps().getFirst();
        assertThat(step.timingSource()).isEqualTo("embedded-stream");
        assertThat(step.ttftMs()).isEqualTo(1000);
        assertThat(step.decodeTps()).isCloseTo(175.0, offset(0.01));
    }

    @Test
    void v0StepDerivesThroughputFromChunkEvents() throws IOException {
        final StreamFacts facts = ingest(Convention.V0,
                stepStart(0, 0, T0),
                v0Chunk(0, 0, T0 + 500),
                v0Chunk(0, 0, T0 + 2500),
                v0Message(0, 0, T0 + 2600, 500));

        final StepRecord step = facts.steps().getFirst();
        assertThat(step.timingSource()).isEqualTo("chunk-events");
        assertThat(step.ttftMs()).isEqualTo(500);
        assertThat(step.decodeTps()).isCloseTo(250.0, offset(0.01));
    }

    @Test
    void aStepWithNoTimedChunksYieldsNullTimingsNotZero() throws IOException {
        final StreamFacts facts = ingest(Convention.V3,
                stepStart(0, 0, T0),
                v3Message(0, 0, T0 + 900, 120, List.of(entryWithoutTime())));

        final StepRecord step = facts.steps().getFirst();
        assertThat(step.decodeTps()).isNull();
        assertThat(step.ttftMs()).isNull();          // 0 would read as "instant first token"
        assertThat(step.timingSource()).isEqualTo("none");
    }

    @Test
    void aStepWithNoStartEventNeverReportsANegativeTtft() throws IOException {
        // The anchor falls back to the message time, which is AFTER the chunk timings stored
        // inside it. The subtraction would be negative, and a negative TTFT pulls the median
        // down without ever looking wrong on screen. Decode is unaffected: it never used the
        // anchor, which is exactly why it is worth keeping.
        final StreamFacts facts = ingest(Convention.V3,
                v3Message(0, 0, T0 + 4000, 350, List.of(chunk(T0 + 1000), chunk(T0 + 3000))));

        final StepRecord step = facts.steps().getFirst();
        assertThat(step.ttftMs()).isNull();
        assertThat(step.decodeTps()).isCloseTo(175.0, offset(0.01));
    }

    @Test
    void headerFieldsAreReadFromTheLineRootNotFromData() throws IOException {
        final StreamFacts facts = ingest(Convention.V3,
                sessionLine("s-1", "/home/dev/demo", 3, "orchestrator"));
        assertThat(facts.session().id()).isEqualTo("s-1");
        assertThat(facts.session().agentPreset()).isEqualTo("orchestrator");
        assertThat(facts.session().delegationDepth()).isEqualTo(3);
        assertThat(facts.session().cwd()).isEqualTo("/home/dev/demo");
    }

    @Test
    void lastRequestContextWinsForModel() throws IOException {
        final StreamFacts facts = ingest(Convention.V0,
                requestContext("p1", "model-a", 131072),
                requestContext("p1", "model-b", 262144));
        assertThat(facts.session().model()).isEqualTo("model-b");
        assertThat(facts.session().contextWindow()).isEqualTo(262144);
    }

    @Test
    void toolCallAndResultJoinOnCallId() throws IOException {
        final StreamFacts facts = ingest(Convention.V0,
                toolCall("c1", "edit", T0, "{\"file_path\":\"/home/dev/demo/a.java\"}"),
                toolResult("c1", T0 + 40, "FS_STALE_VERSION"));

        final ToolCallRecord call = facts.toolCalls().getFirst();
        assertThat(call.name()).isEqualTo("edit");
        assertThat(call.durationMs()).isEqualTo(40);
        assertThat(call.errorCode()).isEqualTo("FS_STALE_VERSION");
        assertThat(call.absolutePath()).isEqualTo("/home/dev/demo/a.java");
        assertThat(facts.errors()).singleElement()
                .satisfies(e -> assertThat(e.code()).isEqualTo("FS_STALE_VERSION"));
        assertThat(facts.touches()).singleElement()
                .satisfies(t -> assertThat(t.op()).isEqualTo(FileTouch.WRITE));
    }

    @Test
    void malformedArgumentsYieldNoPathAndNoException() throws IOException {
        final StreamFacts facts = ingest(Convention.V0,
                toolCall("c2", "edit", T0, "{not json"),
                toolCall("c3", "bash", T0 + 10, "{also not json"));

        // unparseable arguments is data, not an exception: no path, no touch, no shell evidence
        assertThat(facts.toolCalls()).hasSize(2).allSatisfy(c -> assertThat(c.absolutePath()).isNull());
        assertThat(facts.touches()).isEmpty();
        assertThat(facts.shell()).isEmpty();
        assertThat(facts.parseFailures()).isZero();
    }

    private StreamFacts ingest(final Convention convention, final String... lines) throws IOException {
        final String fileName = convention == Convention.V0 ? Convention.FILE_V0 : Convention.FILE_V3;
        final Path file = temp.resolve(fileName);
        writeZstd(file, lines);
        final SessionSource source = new SessionSource(file, "s-demo", "demo-project",
                convention, fileName);
        final SessionIngestor ingestor = new SessionIngestor(mapper, new ShellAnalyzer(mapper));
        return ingestor.ingest(source);
    }

    private void writeZstd(final Path file, final String... lines) throws IOException {
        try (ZstdOutputStream out = new ZstdOutputStream(Files.newOutputStream(file));
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            for (final String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
    }

    private String line(final String type, final long time, final String data) {
        return "{\"type\":\"" + type + "\",\"seq\":" + seq++ + ",\"time\":" + time
                + ",\"data\":" + data + "}";
    }

    private String stepStart(final int turn, final int step, final long time) {
        return line("step/start", time, "{\"turn\":" + turn + ",\"step\":" + step + "}");
    }

    private String v3Message(final int turn, final int step, final long time,
                             final int outputTokens, final List<String> streamEntries) {
        final String data = "{\"turn\":" + turn + ",\"step\":" + step
                + ",\"usage\":{\"inputTokens\":100,\"outputTokens\":" + outputTokens + "}"
                + ",\"stream\":[" + String.join(",", streamEntries) + "]}";
        return line("assistant/message", time, data);
    }

    private String chunk(final long time) {
        return "{\"type\":\"chunk\",\"chunk\":0,\"time\":" + time + "}";
    }

    private String entryWithoutTime() {
        return "{\"type\":\"chunk\",\"chunk\":0}";
    }

    private String v0Chunk(final int turn, final int step, final long time) {
        return line("assistant/chunk", time,
                "{\"turn\":" + turn + ",\"step\":" + step + ",\"chunk\":{\"type\":\"text\",\"index\":0}}");
    }

    private String v0Message(final int turn, final int step, final long time, final int outputTokens) {
        return line("assistant/message", time,
                "{\"turn\":" + turn + ",\"step\":" + step
                        + ",\"usage\":{\"inputTokens\":100,\"outputTokens\":" + outputTokens + "}}");
    }

    /** §3.6: header fields live on the line root; data is empty. */
    private String sessionLine(final String id, final String cwd, final int depth, final String preset) {
        return "{\"type\":\"session\",\"seq\":" + seq++ + ",\"time\":" + T0
                + ",\"id\":\"" + id + "\",\"createdAt\":" + T0
                + ",\"cwd\":\"" + cwd + "\",\"version\":3"
                + ",\"agentPreset\":\"" + preset + "\",\"delegationDepth\":" + depth
                + ",\"data\":{}}";
    }

    private String requestContext(final String provider, final String model, final int window) {
        return line("request/context", T0,
                "{\"provider\":\"" + provider + "\",\"model\":\"" + model
                        + "\",\"contextWindow\":" + window + "}");
    }

    private String toolCall(final String callId, final String name, final long time,
                            final String argumentsJson) {
        return line("tool/call", time,
                "{\"turn\":0,\"step\":0,\"callId\":\"" + callId + "\",\"name\":\"" + name
                        + "\",\"arguments\":" + json(argumentsJson) + "}");
    }

    private String toolResult(final String callId, final long time, final String code) {
        final String error = code == null ? "" : ",\"error\":{\"name\":\"FsError\",\"code\":\"" + code + "\"}";
        return line("tool/result", time,
                "{\"turn\":0,\"step\":0,\"message\":{\"source\":{\"callId\":\"" + callId + "\"}}" + error + "}");
    }

    private String json(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
