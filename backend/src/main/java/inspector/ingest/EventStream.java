package inspector.ingest;

import com.github.luben.zstd.ZstdInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class EventStream implements AutoCloseable {

    private final ObjectMapper mapper;
    private final BufferedReader reader;
    private final Path file;
    private long parseFailures;
    private JsonNode pending;
    private boolean done;

    public EventStream(final Path file, final ObjectMapper mapper) throws IOException {
        this.file = file;
        this.mapper = mapper;
        final InputStream raw = new ZstdInputStream(Files.newInputStream(file));
        this.reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8), 1 << 16);
        advance();
    }

    public boolean hasNext() {
        return pending != null;
    }

    public RawEvent next() {
        if (pending == null) {
            throw new NoSuchElementException("event stream of " + file + " is exhausted");
        }
        final JsonNode current = pending;
        advance();
        return new RawEvent(current.path("type").asText(""),
                current.path("seq").asInt(-1),
                current.path("time").asLong(0L),
                current.path("data"),
                current);
    }

    public long parseFailures() {
        return parseFailures;
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void advance() {
        if (done) {
            pending = null;
            return;
        }
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    pending = mapper.readTree(line);
                    return;
                } catch (JacksonException malformed) {
                    parseFailures++;              // counted, never fatal: one bad line is data
                }
            }
            done = true;
            pending = null;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
