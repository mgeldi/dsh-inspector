package inspector.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

final class EventStreamTest {

    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void eventsComeOutInOrder() throws IOException {
        final Path file = zstdFile("ordered.jsonl.zstd",
                line("tool/call", 1, 1000L),
                line("tool/call", 2, 2000L),
                line("tool/call", 3, 3000L));

        try (EventStream stream = new EventStream(file, mapper)) {
            final List<RawEvent> all = drain(stream);
            assertThat(all).extracting(RawEvent::seq).containsExactly(1, 2, 3);
            assertThat(all).extracting(RawEvent::time).containsExactly(1000L, 2000L, 3000L);
            assertThat(stream.parseFailures()).isZero();
        }
    }

    @Test
    void oneMalformedLineIsCountedAndTheGoodEventsStillArrive() throws IOException {
        final Path file = zstdFile("malformed.jsonl.zstd",
                line("tool/call", 1, 1000L),
                "{ not json at all",
                line("tool/call", 2, 2000L));

        try (EventStream stream = new EventStream(file, mapper)) {
            final List<RawEvent> all = drain(stream);
            assertThat(all).extracting(RawEvent::seq).containsExactly(1, 2);
            assertThat(stream.parseFailures()).isEqualTo(1);
        }
    }

    @Test
    void sessionLineHeaderFieldsLiveOnTheRootNotUnderData() throws IOException {
        // §3.6: the session line carries its header fields on the line root; data is empty.
        final Path file = zstdFile("header.jsonl.zstd",
                "{\"type\":\"session\",\"seq\":0,\"time\":1000,\"id\":\"s-1\","
                        + "\"createdAt\":1760000000000,\"data\":{}}");

        try (EventStream stream = new EventStream(file, mapper)) {
            assertThat(stream.hasNext()).isTrue();
            final RawEvent session = stream.next();
            assertThat(session.rootLong("createdAt")).isEqualTo(1760000000000L);
            assertThat(session.text("/createdAt")).isNull();
        }
    }

    private List<RawEvent> drain(final EventStream stream) {
        final List<RawEvent> all = new ArrayList<>();
        while (stream.hasNext()) {
            all.add(stream.next());
        }
        return all;
    }

    private String line(final String type, final int seq, final long time) {
        return "{\"type\":\"" + type + "\",\"seq\":" + seq + ",\"time\":" + time + ",\"data\":{}}";
    }

    private Path zstdFile(final String name, final String... lines) throws IOException {
        final Path file = temp.resolve(name);
        try (ZstdOutputStream out = new ZstdOutputStream(Files.newOutputStream(file));
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            for (final String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
        return file;
    }
}
