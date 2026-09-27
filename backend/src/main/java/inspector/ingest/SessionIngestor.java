package inspector.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One pass over one stream. Nothing here knows about planes, findings or HTTP: it turns lines
 * into typed facts and forgets the lines. DESIGN.md §4.1 — the decompressed line never escapes.
 */
@Component
public final class SessionIngestor {

    private static final String READ_TOOL = "read";
    private static final String SHELL_TOOL = "bash";
    private static final List<String> PATH_KEYS = List.of("/file_path", "/path", "/notebook_path");
    private static final List<String> RANGE_KEYS = List.of("offset", "limit", "start_line", "end_line", "lines");
    /**
     * The documented shape of a provider failure carried in a turn/end message: an HTTP status,
     * a colon, and the provider's JSON body. Anything else is prose and yields no detail.
     */
    private static final Pattern STATUS_BODY = Pattern.compile("^\\d{3}:\\s*(\\{.*\\})\\s*$", Pattern.DOTALL);
    /** What a detail may look like: a constant, never a sentence. */
    private static final Pattern CONSTANT = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    private final ObjectMapper mapper;
    private final ShellAnalyzer shellAnalyzer;

    public SessionIngestor(final ObjectMapper mapper, final ShellAnalyzer shellAnalyzer) {
        this.mapper = mapper;
        this.shellAnalyzer = shellAnalyzer;
    }

    public StreamFacts ingest(final SessionSource source) {
        final State s = new State(source);
        try (EventStream stream = new EventStream(source.file(), mapper)) {
            while (stream.hasNext()) {
                s.accept(stream.next());
            }
            s.parseFailures = stream.parseFailures();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot ingest " + source.file(), e);
        }
        return s.finish();
    }

    /** Mutable on purpose: one streaming pass over a 5 MB file, no rewinding. */
    private final class State {
        private final SessionSource source;
        private final Map<TurnStep, OpenStep> open = new LinkedHashMap<>();
        private final Map<TurnStep, StepRecord> closed = new LinkedHashMap<>();
        private final List<ToolCallRecord> calls = new ArrayList<>();
        private final Map<String, OpenCall> pendingCalls = new LinkedHashMap<>();
        private final List<FileTouch> touches = new ArrayList<>();
        private final List<ShellEvidence> shell = new ArrayList<>();
        private final List<ErrorEvent> errors = new ArrayList<>();
        private final List<FatalTurn> fatal = new ArrayList<>();
        private final List<RetryEvent> retries = new ArrayList<>();

        private String sessionId;
        private String cwd;
        private long startedAt;
        private Long endedAt;
        private String agentPreset;
        private Integer depth;
        private String model;
        private String provider;
        private Integer contextWindow;
        private long parseFailures;

        State(final SessionSource source) {
            this.source = source;
        }

        void accept(final RawEvent ev) {
            endedAt = ev.time();
            switch (ev.type()) {
                case "session" -> {
                    // §3.6: header fields are on the line root; data is empty.
                    sessionId = ev.rootText("id");
                    cwd = ev.rootText("cwd");
                    startedAt = ev.rootLong("createdAt") == null ? ev.time() : ev.rootLong("createdAt");
                    agentPreset = ev.rootText("agentPreset");
                    depth = ev.rootLong("delegationDepth") == null
                            ? null : ev.rootLong("delegationDepth").intValue();
                }
                case "request/context" -> {
                    model = ev.text("/model");
                    provider = ev.text("/provider");
                    contextWindow = ev.integer("/contextWindow");
                }
                case "step/start" -> open.put(key(ev), new OpenStep(ev.time()));
                case "assistant/chunk" -> {
                    final OpenStep os = open.get(key(ev));
                    if (os != null) {
                        if (os.firstChunk == null) {
                            os.firstChunk = ev.time();
                        }
                        os.lastChunk = ev.time();
                    }
                }
                case "assistant/message" -> closeStep(ev);
                case "step/end" -> {
                    final TurnStep k = key(ev);
                    final StepRecord done = closed.get(k);
                    if (done != null) {
                        closed.put(k, done.withEndedAt(ev.time()));
                    }
                    open.remove(k);
                }
                case "tool/call" -> onCall(ev);
                case "tool/result" -> onResult(ev);
                case "llm/retry" -> retries.add(new RetryEvent(ev.seq(), orZero(ev.integer("/turn")),
                        orZero(ev.integer("/step")), ev.text("/failure/code"), ev.time()));
                case "turn/end" -> onTurnEnd(ev);
                default -> { }                       // unknown types are ignored, never fatal
            }
        }

        private void closeStep(final RawEvent ev) {
            final TurnStep k = key(ev);
            final OpenStep os = open.remove(k);
            final JsonNode usage = ev.data().at("/usage");
            final int out = usage.path("outputTokens").asInt(0);
            final Integer in = usage.path("inputTokens").isNumber() ? usage.path("inputTokens").asInt() : null;

            long start = os != null ? os.start : ev.time();
            Long first;
            Long last;
            String timingSource;
            if (os != null && os.firstChunk != null && os.lastChunk != null) {
                first = os.firstChunk;                       // v0: timings on the events
                last = os.lastChunk;
                timingSource = "chunk-events";
            } else {
                final long[] window = embeddedWindow(ev.data().at("/stream"));   // v3: inside the message
                first = window == null ? null : window[0];
                last = window == null ? null : window[1];
                timingSource = window == null ? "none" : "embedded-stream";
            }

            Double decode = null;
            Integer ttft = null;
            if (first != null && last != null && last > first && out > 0) {
                decode = out / ((last - first) / 1000.0);
                // With no step/start the anchor falls back to the message time, which is *after*
                // the chunk timings it gets compared to. That subtraction is negative, and a
                // negative TTFT drags the median down in silence — an impossible value is
                // reported as unknown instead, while decode survives because it never used start.
                ttft = first >= start ? (int) (first - start) : null;
            }
            closed.put(k, new StepRecord(k.turn(), k.step(), start, null, in, out == 0 ? null : out,
                    decode, ttft, timingSource));
        }

        /** First and last timed chunk entry; entries of other types may carry no time at all. */
        private long[] embeddedWindow(final JsonNode stream) {
            if (!stream.isArray() || stream.isEmpty()) {
                return null;
            }
            Long first = null;
            Long last = null;
            for (final JsonNode entry : stream) {
                if (!"chunk".equals(entry.path("type").asText()) || !entry.path("time").isNumber()) {
                    continue;
                }
                final long t = entry.path("time").asLong();
                if (first == null) {
                    first = t;
                }
                last = t;
            }
            return first == null ? null : new long[]{first, last};
        }

        private void onCall(final RawEvent ev) {
            final String name = ev.text("/name");
            final String arguments = ev.text("/arguments");           // a string containing JSON
            final String absolute = firstPath(arguments);
            final String callId = ev.text("/callId");
            if (callId != null) {
                pendingCalls.put(callId, new OpenCall(ev.seq(), ev.integer("/turn"),
                        ev.integer("/step"), name, ev.time(), absolute));
            }
            if (READ_TOOL.equals(name) && absolute != null) {
                touches.add(new FileTouch(ev.seq(), absolute, FileTouch.READ, isRangedRead(arguments)));
            } else if ("write".equals(name) || "edit".equals(name)) {
                if (absolute != null) {
                    touches.add(new FileTouch(ev.seq(), absolute, FileTouch.WRITE));
                }
            } else if (SHELL_TOOL.equals(name)) {
                shellAnalyzer.analyze(arguments, ev.seq()).ifPresent(shell::add);
            }
        }

        private void onResult(final RawEvent ev) {
            final String callId = ev.text("/message/source/callId");
            final OpenCall open = callId == null ? null : pendingCalls.remove(callId);
            // open == null is the orphan branch: a tool/result whose tool/call never appeared.
            // The row is kept so no outcome is lost, but it is not an observed call — that is
            // what outcome_only records, and it is the one place the marker is set.
            final String code = ev.text("/error/code");
            final String path = open != null && open.absolutePath != null
                    ? open.absolutePath : ev.text("/meta/path");
            calls.add(new ToolCallRecord(open == null ? ev.integer("/turn") : open.turn,
                    open == null ? ev.integer("/step") : open.step,
                    open == null ? ev.seq() : open.seq,
                    open == null ? ev.text("/message/name") : open.name,
                    open == null ? null : open.startedAt,
                    ev.time(),
                    open == null ? null : ev.time() - open.startedAt,
                    code, path, open == null));
            if (code != null) {
                errors.add(new ErrorEvent(open == null ? ev.seq() : open.seq,
                        open == null ? ev.integer("/turn") : open.turn,
                        open == null ? ev.integer("/step") : open.step,
                        open == null ? null : open.name, code, path, ev.time()));
            }
        }

        private void onTurnEnd(final RawEvent ev) {
            if (!"error".equals(ev.text("/reason/kind"))) {
                return;
            }
            // DSH writes {kind, error: {code, message}}. The typed code is authoritative; the
            // message is read only far enough to take the provider's own code out of a body of
            // the documented shape (§5.2 rule 1), and is then dropped. The flat /reason/message
            // location is still read, because an older harness wrote it there.
            final String code = ev.text("/reason/error/code");
            final String message = ev.text("/reason/error/message") != null
                    ? ev.text("/reason/error/message") : ev.text("/reason/message");
            fatal.add(new FatalTurn(orZero(ev.integer("/turn")), code, parseDetail(message), ev.time()));
        }

        StreamFacts finish() {
            final String id = sessionId == null ? source.sessionId() : sessionId;
            final List<StepRecord> steps = new ArrayList<>(closed.values());
            open.forEach((k, os) -> steps.add(new StepRecord(k.turn(), k.step(), os.start, null,
                    null, null, null, null, "none")));
            calls.addAll(pendingCalls.values().stream()
                    .map(c -> new ToolCallRecord(c.turn, c.step, c.seq, c.name, c.startedAt,
                            null, null, null, c.absolutePath, false))
                    .toList());
            return new StreamFacts(
                    new SessionRecord(id, source.sourceFile(), source.projectSlug(),
                            source.convention().name(), startedAt == 0 ? 1L : startedAt, endedAt,
                            agentPreset, depth, model, provider, contextWindow, fatal.size(), cwd),
                    steps, calls, touches, shell, errors, fatal, retries, parseFailures);
        }

        private String firstPath(final String argumentsJson) {
            if (argumentsJson == null || argumentsJson.isBlank()) {
                return null;
            }
            try {
                final JsonNode args = mapper.readTree(argumentsJson);
                for (final String pointer : PATH_KEYS) {
                    final String value = args.at(pointer).asText(null);
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            } catch (JacksonException malformed) {
                return null;   // §3.6: an unparseable arguments string is data, not an exception
            }
            return null;
        }

        /** A read that asked for part of the file: any range argument present. */
        private boolean isRangedRead(final String argumentsJson) {
            if (argumentsJson == null || argumentsJson.isBlank()) {
                return false;
            }
            try {
                final JsonNode args = mapper.readTree(argumentsJson);
                for (final String key : RANGE_KEYS) {
                    final JsonNode value = args.get(key);
                    if (value != null && !value.isNull()) {
                        return true;
                    }
                }
            } catch (JacksonException malformed) {
                return false;
            }
            return false;
        }

        /**
         * The provider's own code from a {@code NNN: {json}} message: the body's {@code code} when
         * it is a string, else its {@code type}, else the same two under a nested {@code error}.
         * A fixed parse with a defined grammar — it matches the shape or yields null, and what it
         * yields must look like a constant, so no sentence can pass through it.
         */
        private String parseDetail(final String message) {
            if (message == null) {
                return null;
            }
            final Matcher m = STATUS_BODY.matcher(message.strip());
            if (!m.matches()) {
                return null;
            }
            final JsonNode body;
            try {
                body = mapper.readTree(m.group(1));
            } catch (JacksonException malformed) {
                return null;
            }
            for (final String pointer : List.of("/code", "/type", "/error/code", "/error/type")) {
                final JsonNode node = body.at(pointer);
                if (node.isString() && CONSTANT.matcher(node.asText()).matches()) {
                    return node.asText();
                }
            }
            return null;
        }
    }

    private record TurnStep(int turn, int step) {
    }

    private static TurnStep key(final RawEvent ev) {
        return new TurnStep(orZero(ev.integer("/turn")), orZero(ev.integer("/step")));
    }

    private static int orZero(final Integer value) {
        return value == null ? 0 : value;
    }

    private static final class OpenStep {
        private final long start;
        private Long firstChunk;
        private Long lastChunk;

        OpenStep(final long start) {
            this.start = start;
        }
    }

    private record OpenCall(int seq, Integer turn, Integer step, String name,
                            long startedAt, String absolutePath) {
    }
}
