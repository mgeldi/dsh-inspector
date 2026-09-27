package inspector.fixturegen;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates the synthetic fixture corpus the dashboard, the count-band assertions and the
 * read-side tests run against. The corpus is committed next to this generator so provenance
 * is reproducible (DESIGN.md §11, §12: a fresh clone shows a populated dashboard on first
 * run, with both schema conventions and every detector's target pattern present).
 *
 * <p><b>Every path, project name, model and preset here is invented.</b> The generator never
 * reads the real corpus and never copies anything: it only emits the event shapes the
 * ingestor understands, under invented working directories ({@code /home/dev/...}).
 *
 * <p><b>Determinism.</b> No clock is read and no randomness is used. All event times derive
 * from the fixed base {@link #T0} (2026-09-01T00:00:00Z) and are spread over
 * 2026-09-01 to 2026-09-14, so a 30-day time filter run on or shortly after the commit's
 * date still shows the whole corpus. Regenerating produces byte-identical files: zstd-jni
 * compresses a fixed byte sequence with fixed settings, and every map is a
 * {@link LinkedHashMap}, so nothing in the output depends on iteration order or host state.
 *
 * <p><b>Regenerate</b> from the {@code backend/} directory. The class lives under
 * {@code src/test} so it stays out of the deployable artifact — it is development tooling, and
 * 634 lines of it is the largest class in the project — and the {@code exec-maven-plugin} block
 * in {@code pom.xml} runs it on the test classpath:
 *
 * <pre>{@code
 * mvn -q test-compile exec:java
 * }</pre>
 *
 * or, without Maven, against the compiled test classes plus the two runtime dependencies:
 *
 * <pre>{@code
 * java -cp target/test-classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
 *     inspector.fixturegen.FixtureGenerator
 * }</pre>
 *
 * <p>The generator deletes the target directory first, so a regeneration can never leave a
 * stale file behind; run it and commit the result together.
 */
public final class FixtureGenerator {

    /** 2026-09-01T00:00:00Z. Fixed on purpose: the corpus must regenerate identically. */
    private static final long T0 = 1_788_220_800_000L;
    private static final long DAY = 86_400_000L;
    private static final long HOUR = 3_600_000L;
    private static final long MIN = 60_000L;

    private FixtureGenerator() {
    }

    public static void main(final String[] args) {
        final Path sessions = Path.of(args.length > 0 ? args[0] : "fixtures/sessions");
        final Path sessionsB = Path.of(args.length > 1 ? args[1] : "fixtures/sessions-b");
        System.out.printf("wrote %d session files under %s%n", generate(sessions), sessions);
        System.out.printf("wrote %d session files under %s%n", generateB(sessionsB), sessionsB);
    }

    /**
     * The main corpus: 11 sessions in 12 streams. s-06 exists in both conventions with the
     * same session id and independent seq spaces; the other ten each hold one file. The
     * detector targets are s-01 (direct mutation, HIGH), s-02 (vcs restore, HIGH),
     * s-03 (mention without mutation, EXTERNAL), s-04 (contamination: exactly one fatal
     * turn among documentation mentions), s-05 (retry storm + credential missing),
     * s-07 (direct mutation via a python heredoc, embedded v3 timings, isSeeded) and
     * s-08 (v3 step whose stream entries carry no time). s-06/v0 and s-11 add the
     * model-misuse plane; s-09 has no request/context (a NULL model must stay reachable);
     * s-08 has no agentPreset (a NULL preset, as v3 headers sometimes omit it). s-10 and s-11 are
     * subagents (delegation depth 1) on a second provider route, so the provider and role
     * cohorts have two rows. s-15 walks every edit-miss category, two shell edits that count and
     * one file operation that does not, and a fatal turn whose provider body carries a type.
     */
    static int generate(final Path root) {
        clean(root);
        int files = 0;
        files += s01(root);            // v0: sed -i then edit  -> FS_STALE_VERSION, DIRECT_MUTATION 0.9
        files += s02(root);            // v0: git checkout then edit -> FS_STALE_VERSION, VCS_RESTORE 0.9
        files += s03(root);            // v0: node scripts/... mention only -> EXTERNAL, null confidence
        files += s04(root);            // v0: one real fatal turn, three documentation mentions
        files += s05(root);            // v0: three llm/retry in one step + WEB_PROVIDER_CREDENTIAL_MISSING
        files += s06(root);            // v0 + v3, same session id, independent seq spaces
        files += s07(root);            // v3: isSeeded, embedded stream timings, python write cause
        files += s08(root);            // v3: step whose stream entries carry no time -> NULL timings
        files += s09(root);            // v0: clean, no request/context -> NULL model
        files += s10(root);            // v3: clean, embedded timings
        files += s11(root);            // v0: one SEARCH_FAILED -> MODEL_MISUSE (subagent, second route)
        files += s15(root);            // v0: every edit-miss category, shell edits, a 503 fatal turn
        return files;
    }

    /**
     * The second corpus, indexed under a different harness version so the cohort route has
     * two rows to compare. Three v0 sessions: one with a guard violation, one with a retry
     * storm plus a guard-plane error, one clean.
     */
    static int generateB(final Path root) {
        clean(root);
        int files = 0;
        files += s12(root);            // v0: sed -i then edit -> DIRECT_MUTATION 0.9
        files += s13(root);            // v0: two llm/retry in one step + FS_NOT_OBSERVED
        files += s14(root);            // v0: clean
        return files;
    }

    // ------------------------------------------------------------------ scenarios

    private static int s01(final Path root) {
        final long b = T0 + 0 * DAY + 8 * HOUR + 30 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-01", b, "/home/dev/demo", 0, "builder", 0, null);
        w.requestContext(b + 1_000, "local", "demo-brain-27b", 131_072);
        normalStep0(w, 0, b + 2_000, "/home/dev/demo/build.gradle");
        long t = b + 40_000;
        w.stepStart(1, 0, t);
        w.call("read", 1, 0, t + 1_000, args("file_path", "/home/dev/demo/App.java"));
        w.result(t + 3_000, null);
        w.call("bash", 1, 0, t + 4_000,
                args("command", "sed -i 's/legacy/current/' /home/dev/demo/App.java"));
        w.result(t + 6_000, null);
        w.call("edit", 1, 0, t + 7_000, args("file_path", "/home/dev/demo/App.java"));
        w.result(t + 9_000, "FS_STALE_VERSION");
        w.stepEnd(1, 0, t + 9_500);
        return write(root, "demo-app", "s-01", "session.jsonl.zstd", w);
    }

    private static int s02(final Path root) {
        final long b = T0 + 1 * DAY + 9 * HOUR + 12 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-02", b, "/home/dev/demo", 0, "builder", 0, null);
        w.requestContext(b + 1_000, "local", "demo-flash-8b", 262_144);
        normalStep0(w, 0, b + 2_000, "/home/dev/demo/Makefile");
        long t = b + 35_000;
        w.stepStart(1, 0, t);
        w.call("read", 1, 0, t + 1_000, args("file_path", "/home/dev/demo/README.md"));
        w.result(t + 3_000, null);
        w.call("bash", 1, 0, t + 4_000, args("command", "git checkout HEAD -- README.md"));
        w.result(t + 6_000, null);
        w.call("edit", 1, 0, t + 7_000, args("file_path", "/home/dev/demo/README.md"));
        w.result(t + 9_000, "FS_STALE_VERSION");
        w.stepEnd(1, 0, t + 9_500);
        return write(root, "demo-app", "s-02", "session.jsonl.zstd", w);
    }

    private static int s03(final Path root) {
        final long b = T0 + 2 * DAY + 10 * HOUR + 5 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-03", b, "/home/dev/demo", 0, "planner", 0, null);
        w.requestContext(b + 1_000, "local", "demo-brain-27b", 131_072);
        normalStep0(w, 0, b + 2_000, "/home/dev/demo/package.json");
        long t = b + 41_000;
        w.stepStart(1, 0, t);
        w.call("write", 1, 0, t + 1_000, args("file_path", "/home/dev/demo/audit.mjs"));
        w.result(t + 3_000, null);
        // Executing a script never moves the mtime of the file it audits: the command only
        // *mentions* the path in the window, which is the measured false positive of §5.3.
        w.call("bash", 1, 0, t + 5_000, args("command", "node scripts/audit.mjs"));
        w.result(t + 8_000, null);
        w.call("edit", 1, 0, t + 9_000, args("file_path", "/home/dev/demo/audit.mjs"));
        w.result(t + 11_000, "FS_STALE_VERSION");
        w.stepEnd(1, 0, t + 11_500);
        return write(root, "demo-app", "s-03", "session.jsonl.zstd", w);
    }

    private static int s04(final Path root) {
        final long b = T0 + 3 * DAY + 11 * HOUR + 40 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-04", b, "/home/dev/demo", 0, "builder", 0, null);
        w.requestContext(b + 1_000, "local", "demo-brain-27b", 131_072);
        normalStep0(w, 0, b + 2_000, "/home/dev/demo/notes.md");
        long t = b + 30_000;
        w.stepStart(1, 0, t);
        w.message0(1, 0, t + 2_500, 900, 95);
        w.stepEnd(1, 0, t + 2_900);
        // Three user messages merely *cite* the code in documentation text. The context
        // contains documentation naming an error code (§5.2): none of these may produce a
        // finding; the only fatal turn in the stream is the one below.
        w.userMessage(t + 4_000, "Per the provider documentation, media_budget_exceeded is returned"
                + " when the cumulative vision envelope of a turn is crossed.");
        w.userMessage(t + 5_000, "The error table lists media_budget_exceeded under provider errors;"
                + " it is not a harness fault code.");
        w.userMessage(t + 6_000, "Re-reading the spec: on media_budget_exceeded the turn dies and a"
                + " retry would resend the images, so the only fix is to stop carrying them.");
        w.stepStart(2, 0, t + 8_000);
        w.turnEnd(2, t + 9_000, "INVALID_REQUEST", "400: {\"code\":\"media_budget_exceeded\"}");
        return write(root, "demo-app", "s-04", "session.jsonl.zstd", w);
    }

    private static int s05(final Path root) {
        final long b = T0 + 4 * DAY + 14 * HOUR + 22 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-05", b, "/home/dev/demo", 0, "builder", 0, null);
        w.requestContext(b + 1_000, "local", "demo-flash-8b", 262_144);
        normalStep0(w, 0, b + 2_000, "/home/dev/demo/status.txt");
        long t = b + 45_000;
        w.stepStart(1, 0, t);
        w.retry(1, 0, t + 2_000, "TIMEOUT");
        w.retry(1, 0, t + 9_000, "SERVER");
        w.retry(1, 0, t + 16_000, "TIMEOUT");
        w.message0(1, 0, t + 18_000, 1_100, 130);
        w.stepEnd(1, 0, t + 18_500);
        t = b + 70_000;
        w.stepStart(2, 0, t);
        w.call("web", 2, 0, t + 1_000, args("query", "demo provider status page"));
        w.result(t + 3_000, "WEB_PROVIDER_CREDENTIAL_MISSING");
        w.stepEnd(2, 0, t + 3_500);
        return write(root, "demo-app", "s-05", "session.jsonl.zstd", w);
    }

    /**
     * One session id in two files, each file with its own seq space starting at 0 and
     * overlapping turn numbers (§3.2). The v0 stream carries the only finding
     * (FS_NOT_FOUND on a missing file); the v3 stream is clean throughput.
     */
    private static int s06(final Path root) {
        final long b = T0 + 5 * DAY + 15 * HOUR + 8 * MIN;

        final EventWriter v0 = new EventWriter();
        v0.session("s-06", b, "/home/dev/two-files", 0, "builder", 0, null);
        v0.requestContext(b + 1_000, "local", "demo-brain-27b", 131_072);
        normalStep0(v0, 0, b + 2_000, "/home/dev/two-files/README.md");
        long t = b + 33_000;
        v0.stepStart(1, 0, t);
        v0.call("edit", 1, 0, t + 1_000, args("file_path", "/home/dev/two-files/missing.txt"));
        v0.result(t + 3_000, "FS_NOT_FOUND");
        v0.stepEnd(1, 0, t + 3_500);
        write(root, "demo-two-files", "s-06", "session.jsonl.zstd", v0);

        final EventWriter v3 = new EventWriter();
        v3.session("s-06", b, "/home/dev/two-files", 3, "builder", 0, Boolean.FALSE);
        v3.requestContext(b + 1_000, "local", "demo-flash-8b", 262_144);
        normalStep3(v3, 0, b + 2_000, "/home/dev/two-files/README.md");
        normalStep3(v3, 1, b + 38_000, "/home/dev/two-files/README.md");
        return 1 + write(root, "demo-two-files", "s-06", "session.v3.jsonl.zstd", v3);
    }

    private static int s07(final Path root) {
        final long b = T0 + 7 * DAY + 8 * HOUR + 55 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-07", b, "/home/dev/demo-v3", 3, "planner", 0, Boolean.TRUE);
        w.requestContext(b + 1_000, "local", "demo-brain-27b", 131_072);
        normalStep3(w, 0, b + 2_000, "/home/dev/demo-v3/README.md");
        long t = b + 42_000;
        w.stepStart(1, 0, t);
        w.call("read", 1, 0, t + 1_000, args("file_path", "/home/dev/demo-v3/config.yaml"));
        w.result(t + 3_000, null);
        // The §5.3 named example: an embedded python write, caught by the heredoc branch of
        // the shell analyzer. The command carries the absolute path, so attribution is HIGH.
        w.call("bash", 1, 0, t + 4_000, args("command",
                "python -c \"open('/home/dev/demo-v3/config.yaml', 'w').write('demo')\""));
        w.result(t + 6_000, null);
        w.call("write", 1, 0, t + 7_000, args("file_path", "/home/dev/demo-v3/config.yaml"));
        w.result(t + 9_000, "FS_STALE_VERSION");
        w.stepEnd(1, 0, t + 9_500);
        return write(root, "demo-v3", "s-07", "session.v3.jsonl.zstd", w);
    }

    private static int s08(final Path root) {
        final long b = T0 + 8 * DAY + 9 * HOUR + 47 * MIN;
        final EventWriter w = new EventWriter();
        // v3 headers omit agentPreset sometimes: this session has no preset, a NULL that
        // must stay reachable as the "unknown" bucket.
        w.session("s-08", b, "/home/dev/demo-v3", 3, null, 0, Boolean.FALSE);
        w.requestContext(b + 1_000, "local", "demo-flash-8b", 262_144);
        w.stepStart(0, 0, b + 2_000);
        w.call("read", 0, 0, b + 3_000, args("file_path", "/home/dev/demo-v3/README.md"));
        w.result(b + 5_000, null);
        // Stream entries without a time field: no window can be derived, so decode and ttft
        // must stay NULL, not zero.
        w.message3(0, 0, b + 7_000, 700, 140,
                entry("type", "reasoning-chunks"), entry("type", "chunk"));
        w.stepEnd(0, 0, b + 7_500);
        normalStep3(w, 1, b + 40_000, "/home/dev/demo-v3/README.md");
        return write(root, "demo-v3", "s-08", "session.v3.jsonl.zstd", w);
    }

    private static int s09(final Path root) {
        final long b = T0 + 9 * DAY + 10 * HOUR + 33 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-09", b, "/home/dev/plain", 0, "builder", 0, null);
        // Deliberately no request/context event: the model stays NULL and the "unknown"
        // bucket must still list this session in every filtered view.
        w.stepStart(0, 0, b + 2_000);
        w.call("read", 0, 0, b + 3_000, args("file_path", "/home/dev/plain/notes.txt"));
        w.result(b + 5_000, null);
        w.chunk(0, 0, b + 6_000);
        w.chunk(0, 0, b + 8_200);
        w.message0(0, 0, b + 8_400, 600, 110);
        w.stepEnd(0, 0, b + 8_600);
        long t = b + 36_000;
        w.stepStart(1, 0, t);
        w.call("read", 1, 0, t + 1_000, args("file_path", "/home/dev/plain/notes.txt"));
        w.result(t + 3_000, null);
        w.call("write", 1, 0, t + 4_000, args("file_path", "/home/dev/plain/notes.txt"));
        w.result(t + 6_000, null);
        w.stepEnd(1, 0, t + 6_500);
        return write(root, "demo-plain", "s-09", "session.jsonl.zstd", w);
    }

    private static int s10(final Path root) {
        final long b = T0 + 10 * DAY + 11 * HOUR + 26 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-10", b, "/home/dev/plain", 3, "planner", 1, Boolean.TRUE);
        w.requestContext(b + 1_000, "local-impl", "demo-brain-27b", 131_072);
        normalStep3(w, 0, b + 2_000, "/home/dev/plain/README.md");
        normalStep3(w, 1, b + 40_000, "/home/dev/plain/README.md");
        return write(root, "demo-plain", "s-10", "session.v3.jsonl.zstd", w);
    }

    private static int s11(final Path root) {
        final long b = T0 + 11 * DAY + 13 * HOUR + 14 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-11", b, "/home/dev/plain", 0, "builder", 1, null);
        w.requestContext(b + 1_000, "local-impl", "demo-flash-8b", 262_144);
        normalStep0(w, 0, b + 2_000, "/home/dev/plain/src/app.mjs");
        long t = b + 37_000;
        w.stepStart(1, 0, t);
        w.call("search", 1, 0, t + 1_000,
                args("pattern", "demo", "path", "/home/dev/plain/src"));
        w.result(t + 3_000, "SEARCH_FAILED");
        w.stepEnd(1, 0, t + 3_500);
        return write(root, "demo-plain", "s-11", "session.jsonl.zstd", w);
    }

    /**
     * The model-side patterns the edit-miss and shell-edit detectors explain, in one stream:
     * an edit that quotes the file as it was before the model's own edit (MISS_AFTER_EDIT), the
     * same edit retried blind (REPEATED_MISS), a miss right after a read (MISS_AFTER_READ), a
     * heredoc overwrite and a script write of files the file tools had read (two shell edits),
     * and a copy of a tracked file that is not an edit of it. The turn then dies on a 503 whose
     * body names the provider's own type.
     */
    private static int s15(final Path root) {
        final long b = T0 + 12 * DAY + 16 * HOUR + 3 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-15", b, "/home/dev/plain", 0, "builder", 0, null);
        w.requestContext(b + 1_000, "local", "demo-brain-27b", 131_072);
        normalStep0(w, 0, b + 2_000, "/home/dev/plain/src/app.mjs");
        long t = b + 30_000;
        w.stepStart(1, 0, t);
        w.call("read", 1, 0, t + 1_000, args("file_path", "/home/dev/plain/src/app.mjs"));
        w.result(t + 1_500, null);
        w.call("edit", 1, 0, t + 2_000, args("file_path", "/home/dev/plain/src/app.mjs"));
        w.result(t + 2_500, null);
        w.call("edit", 1, 0, t + 3_000, args("file_path", "/home/dev/plain/src/app.mjs"));
        w.result(t + 3_500, "FS_EDIT_NOT_FOUND");             // MISS_AFTER_EDIT
        w.call("edit", 1, 0, t + 4_000, args("file_path", "/home/dev/plain/src/app.mjs"));
        w.result(t + 4_500, "FS_EDIT_NOT_FOUND");             // REPEATED_MISS
        w.call("read", 1, 0, t + 5_000, args("file_path", "/home/dev/plain/src/app.mjs"));
        w.result(t + 5_500, null);
        w.call("edit", 1, 0, t + 6_000, args("file_path", "/home/dev/plain/src/app.mjs"));
        w.result(t + 6_500, "FS_EDIT_NOT_FOUND");             // MISS_AFTER_READ
        w.call("read", 1, 0, t + 7_000, args("file_path", "/home/dev/plain/notes.md"));
        w.result(t + 7_500, null);
        w.call("bash", 1, 0, t + 8_000, args("command",
                "cat > /home/dev/plain/notes.md <<'EOF'\n# notes\nrewritten\nEOF"));
        w.result(t + 8_500, null);                            // shell-edit, REDIRECT, full match
        w.call("bash", 1, 0, t + 9_000, args("command",
                "python3 - <<'EOF'\nfrom pathlib import Path\nPath('src/app.mjs').write_text('x')\nEOF"));
        w.result(t + 9_500, null);                            // shell-edit, SCRIPT, suffix match
        w.call("bash", 1, 0, t + 10_000, args("command",
                "cp /home/dev/plain/src/app.mjs /home/dev/plain/src/app.mjs.bak"));
        w.result(t + 10_500, null);                           // a copy: not a shell edit
        w.stepEnd(1, 0, t + 11_000);
        w.stepStart(2, 0, t + 12_000);
        w.turnEnd(2, t + 13_000, "SERVER",
                "503: {\"code\":503,\"message\":\"Loading model\",\"type\":\"unavailable_error\"}");
        return write(root, "demo-plain", "s-15", "session.jsonl.zstd", w);
    }

    private static int s12(final Path root) {
        final long b = T0 + 12 * DAY + 8 * HOUR + 59 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-12", b, "/home/dev/demo-b", 0, "builder", 0, null);
        w.requestContext(b + 1_000, "local", "demo-brain-27b", 131_072);
        normalStep0(w, 0, b + 2_000, "/home/dev/demo-b/README.md");
        long t = b + 31_000;
        w.stepStart(1, 0, t);
        w.call("read", 1, 0, t + 1_000, args("file_path", "/home/dev/demo-b/main.mjs"));
        w.result(t + 3_000, null);
        w.call("bash", 1, 0, t + 4_000,
                args("command", "sed -i 's/old/new/' /home/dev/demo-b/main.mjs"));
        w.result(t + 6_000, null);
        w.call("edit", 1, 0, t + 7_000, args("file_path", "/home/dev/demo-b/main.mjs"));
        w.result(t + 9_000, "FS_STALE_VERSION");
        w.stepEnd(1, 0, t + 9_500);
        return write(root, "demo-b", "s-12", "session.jsonl.zstd", w);
    }

    private static int s13(final Path root) {
        final long b = T0 + 12 * DAY + 15 * HOUR + 41 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-13", b, "/home/dev/demo-b", 0, "builder", 0, null);
        w.requestContext(b + 1_000, "local", "demo-flash-8b", 262_144);
        normalStep0(w, 0, b + 2_000, "/home/dev/demo-b/README.md");
        long t = b + 34_000;
        w.stepStart(1, 0, t);
        w.retry(1, 0, t + 2_000, "TRANSPORT");
        w.retry(1, 0, t + 8_000, "SERVER");
        w.message0(1, 0, t + 10_000, 950, 105);
        w.stepEnd(1, 0, t + 10_500);
        t = b + 58_000;
        w.stepStart(2, 0, t);
        w.call("edit", 2, 0, t + 1_000, args("file_path", "/home/dev/demo-b/ghost.txt"));
        w.result(t + 3_000, "FS_NOT_OBSERVED");
        w.stepEnd(2, 0, t + 3_500);
        return write(root, "demo-b", "s-13", "session.jsonl.zstd", w);
    }

    private static int s14(final Path root) {
        final long b = T0 + 13 * DAY + 9 * HOUR + 18 * MIN;
        final EventWriter w = new EventWriter();
        w.session("s-14", b, "/home/dev/demo-b", 0, "builder", 0, null);
        w.requestContext(b + 1_000, "local", "demo-flash-8b", 262_144);
        normalStep0(w, 0, b + 2_000, "/home/dev/demo-b/README.md");
        long t = b + 39_000;
        w.stepStart(1, 0, t);
        w.call("read", 1, 0, t + 1_000, args("file_path", "/home/dev/demo-b/notes.txt"));
        w.result(t + 3_000, null);
        w.call("write", 1, 0, t + 4_000, args("file_path", "/home/dev/demo-b/notes.txt"));
        w.result(t + 6_000, null);
        w.stepEnd(1, 0, t + 6_500);
        return write(root, "demo-b", "s-14", "session.jsonl.zstd", w);
    }

    /** A v0 step with two chunk events (timings on the events) and one closing message. */
    private static void normalStep0(final EventWriter w, final int turn, final long start,
                                    final String readPath) {
        w.stepStart(turn, 0, start);
        w.call("read", turn, 0, start + 1_000, args("file_path", readPath));
        w.result(start + 2_500, null);
        w.chunk(turn, 0, start + 3_200);
        w.chunk(turn, 0, start + 5_400);
        w.message0(turn, 0, start + 5_600, 800, 155);
        w.stepEnd(turn, 0, start + 5_800);
    }

    /** A v3 step with embedded stream timings inside the message. */
    private static void normalStep3(final EventWriter w, final int turn, final long start,
                                    final String readPath) {
        w.stepStart(turn, 0, start);
        w.call("read", turn, 0, start + 1_000, args("file_path", readPath));
        w.result(start + 2_500, null);
        w.message3(turn, 0, start + 4_300, 800, 190,
                entry("type", "chunk", "time", start + 900),
                entry("type", "chunk", "time", start + 3_600));
        w.stepEnd(turn, 0, start + 4_500);
    }

    // ------------------------------------------------------------------ plumbing

    private static int write(final Path root, final String slug, final String id,
                             final String file, final EventWriter w) {
        w.zstd(root.resolve(slug).resolve(id).resolve(file));
        return 1;
    }

    /** LinkedHashMaps only: the byte output must not depend on map iteration order. */
    private static Map<String, Object> args(final String... keysAndValues) {
        final Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    /** A stream entry; values may be numeric (a time) as well as string. */
    private static Map<String, Object> entry(final Object... keysAndValues) {
        final Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return m;
    }

    private static void clean(final Path root) {
        if (Files.isDirectory(root)) {
            try {
                Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final java.nio.file.attribute.BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(final Path dir, final IOException exc)
                            throws IOException {
                        if (exc != null) {
                            throw exc;
                        }
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("cannot clean " + root, e);
            }
        }
    }

    /**
     * Builds one JSONL line per event. The seq is assigned in emission order, so each file
     * starts its own seq space at 1 (the ingestor keys a stream by (sessionId, sourceFile)).
     */
    private static final class EventWriter {

        private final ObjectMapper mapper = new ObjectMapper();
        private final List<String> lines = new ArrayList<>();
        private int seq;
        private int callCount;

        void session(final String id, final long createdAt, final String cwd, final int version,
                     final String preset, final Integer depth, final Boolean isSeeded) {
            final Map<String, Object> root = line("session", createdAt);
            root.put("id", id);
            root.put("createdAt", createdAt);
            root.put("cwd", cwd);
            root.put("version", version);
            if (preset != null) {
                root.put("agentPreset", preset);
            }
            root.put("delegationDepth", depth);
            if (isSeeded != null) {
                root.put("isSeeded", isSeeded);
            }
            root.put("data", new LinkedHashMap<String, Object>());
            lines.add(mapper.writeValueAsString(root));
        }

        void requestContext(final long t, final String provider, final String model, final int window) {
            data("request/context", t, m -> {
                m.put("provider", provider);
                m.put("model", model);
                m.put("contextWindow", window);
            });
        }

        void stepStart(final int turn, final int step, final long t) {
            data("step/start", t, m -> {
                m.put("turn", turn);
                m.put("step", step);
            });
        }

        void chunk(final int turn, final int step, final long t) {
            data("assistant/chunk", t, m -> {
                m.put("turn", turn);
                m.put("step", step);
            });
        }

        void message0(final int turn, final int step, final long t, final int in, final int out) {
            data("assistant/message", t, m -> {
                m.put("turn", turn);
                m.put("step", step);
                m.put("usage", usage(in, out));
            });
        }

        void message3(final int turn, final int step, final long t, final int in, final int out,
                      final Map<String, Object>... streamEntries) {
            data("assistant/message", t, m -> {
                m.put("turn", turn);
                m.put("step", step);
                m.put("usage", usage(in, out));
                m.put("stream", List.of(streamEntries));
            });
        }

        void stepEnd(final int turn, final int step, final long t) {
            data("step/end", t, m -> {
                m.put("turn", turn);
                m.put("step", step);
            });
        }

        void call(final String name, final int turn, final int step, final long t,
                  final Map<String, Object> toolArgs) {
            final String callId = "c-" + (++callCount);
            data("tool/call", t, m -> {
                m.put("turn", turn);
                m.put("step", step);
                m.put("callId", callId);
                m.put("name", name);
                // arguments is a *string* containing JSON (§3.6), unparseable ones allowed.
                m.put("arguments", mapper.writeValueAsString(toolArgs));
            });
        }

        /** The result of the most recent call; null means a plain success. */
        void result(final long t, final String errorCode) {
            final String callId = "c-" + callCount;
            data("tool/result", t, m -> {
                final Map<String, Object> message = new LinkedHashMap<>();
                final Map<String, Object> source = new LinkedHashMap<>();
                source.put("callId", callId);
                message.put("source", source);
                m.put("message", message);
                if (errorCode != null) {
                    final Map<String, Object> error = new LinkedHashMap<>();
                    error.put("name", "FsError");
                    error.put("code", errorCode);
                    m.put("error", error);
                }
            });
        }

        void retry(final int turn, final int step, final long t, final String code) {
            data("llm/retry", t, m -> {
                m.put("turn", turn);
                m.put("step", step);
                final Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("code", code);
                m.put("failure", failure);
            });
        }

        void userMessage(final long t, final String text) {
            data("user/message", t, m -> m.put("text", text));
        }

        /**
         * The shape DSH writes: {@code reason = {kind: "error", error: {code, message}}}. An
         * earlier version of this generator put the message flat on the reason, the ingestor read
         * it from there, and the two agreed with each other while every real fatal turn went
         * unparsed — the fixture has to be the log's shape, not the parser's.
         */
        void turnEnd(final int turn, final long t, final String code, final String errorMessage) {
            data("turn/end", t, m -> {
                m.put("turn", turn);
                final Map<String, Object> error = new LinkedHashMap<>();
                error.put("code", code);
                error.put("message", errorMessage);
                final Map<String, Object> reason = new LinkedHashMap<>();
                reason.put("kind", "error");
                reason.put("error", error);
                m.put("reason", reason);
            });
        }

        void zstd(final Path file) {
            try {
                Files.createDirectories(file.getParent());
                try (ZstdOutputStream out = new ZstdOutputStream(Files.newOutputStream(file))) {
                    for (final String line : lines) {
                        out.write(line.getBytes(StandardCharsets.UTF_8));
                        out.write('\n');
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write " + file, e);
            }
        }

        private Map<String, Object> line(final String type, final long t) {
            final Map<String, Object> root = new LinkedHashMap<>();
            root.put("type", type);
            root.put("seq", ++seq);
            root.put("time", t);
            return root;
        }

        private void data(final String type, final long t, final DataFill fill) {
            final Map<String, Object> root = line(type, t);
            final Map<String, Object> data = new LinkedHashMap<>();
            fill.fill(data);
            root.put("data", data);
            lines.add(mapper.writeValueAsString(root));
        }

        private static Map<String, Object> usage(final int in, final int out) {
            final Map<String, Object> u = new LinkedHashMap<>();
            u.put("inputTokens", in);
            u.put("outputTokens", out);
            return u;
        }

        @FunctionalInterface
        private interface DataFill {
            void fill(Map<String, Object> data);
        }
    }
}
