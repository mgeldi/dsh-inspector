package inspector.detect;

import inspector.ingest.ErrorEvent;
import inspector.ingest.FileTouch;
import inspector.ingest.StreamFacts;
import inspector.ingest.ToolCallRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Failed edits, explained by what the model did to the same file just before (DESIGN.md §5.4).
 *
 * <p>{@code FS_EDIT_NOT_FOUND} is the most frequent model-side error on the measured corpus, and
 * as a bare count it says nothing a harness change could act on. The previous file-tool operation
 * on the same path, in the same stream, splits it into five different failures:
 * <ul>
 *   <li>{@link Category#REPEATED_MISS} — the previous operation was this same failure: the model
 *       retried without looking. A tool-side hint ("the nearest match is at line N") is the
 *       harness change that would remove it.</li>
 *   <li>{@link Category#MISS_AFTER_EDIT} — the previous operation was its own successful edit or
 *       write: it quoted the file as it was before its own change. The largest group on the
 *       measured corpus (47 of 107), and the one a post-edit excerpt in the tool result targets.</li>
 *   <li>{@link Category#MISS_AFTER_PARTIAL_READ} — it had read a range of the file and quoted
 *       text from outside it, or from memory. 43 of the 44 misses after a read on the measured
 *       corpus were this: a tool-side hint naming the range that was read is the change it
 *       argues for.</li>
 *   <li>{@link Category#MISS_AFTER_READ} — it had read the whole file and still misquoted it.
 *       (Not line-number prefixes: DSH's read output carries none, and none of the measured
 *       misses contained one.)</li>
 *   <li>{@link Category#MISS_UNREAD} — nothing on that path came before it in this stream.</li>
 * </ul>
 * Operations that failed for another reason are skipped when looking back: they neither changed
 * the file nor showed the model its content.
 *
 * <p>The category is a fact of the sequence, not an inference, so confidence is {@code HIGH}.
 * One finding per source event, as everywhere: this detector owns the code, so
 * {@code error-plane} does not also emit it.
 */
@Component
public final class EditMissDetector implements Detector {

    public static final String ID = "edit-miss";
    static final String CODE = "FS_EDIT_NOT_FOUND";
    private static final Set<String> FILE_TOOLS = Set.of("read", "write", "edit");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> ownsToolCodes() {
        return Set.of(CODE);
    }

    @Override
    public List<Finding> detect(final StreamFacts facts) {
        final Map<String, List<ToolCallRecord>> byPath = new HashMap<>();
        facts.toolCalls().stream()
                .filter(c -> c.absolutePath() != null && c.name() != null && FILE_TOOLS.contains(c.name()))
                .sorted(Comparator.comparingInt(ToolCallRecord::seq))
                .forEach(c -> byPath.computeIfAbsent(c.absolutePath(), k -> new ArrayList<>()).add(c));

        final Set<Integer> rangedReads = new HashSet<>();
        facts.touches().stream().filter(FileTouch::partial).forEach(t -> rangedReads.add(t.seq()));

        final List<Finding> findings = new ArrayList<>();
        for (final ErrorEvent error : facts.errors()) {
            if (!CODE.equals(error.code())) {
                continue;
            }
            final ToolCallRecord previous = previousOperation(byPath.get(error.absolutePath()), error.seq());
            final Category category = categorise(previous, rangedReads);
            findings.add(new Finding(ID, Plane.MODEL_MISUSE, category, error.code(), null,
                    Confidence.HIGH, error.absolutePath(), error.seq(), null,
                    previous == null ? null : previous.seq(), error.occurredAt(),
                    summary(error, category, previous), List.of()));
        }
        return findings;
    }

    /** The last operation before {@code seq} that either succeeded or was this same miss. */
    private static ToolCallRecord previousOperation(final List<ToolCallRecord> calls, final int seq) {
        if (calls == null) {
            return null;
        }
        ToolCallRecord found = null;
        for (final ToolCallRecord call : calls) {
            if (call.seq() >= seq) {
                break;
            }
            if (call.errorCode() == null || CODE.equals(call.errorCode())) {
                found = call;
            }
        }
        return found;
    }

    static Category categorise(final ToolCallRecord previous, final Set<Integer> rangedReads) {
        if (previous == null) {
            return Category.MISS_UNREAD;
        }
        if (CODE.equals(previous.errorCode())) {
            return Category.REPEATED_MISS;
        }
        if ("read".equals(previous.name())) {
            return rangedReads.contains(previous.seq()) ? Category.MISS_AFTER_PARTIAL_READ : Category.MISS_AFTER_READ;
        }
        return Category.MISS_AFTER_EDIT;
    }

    private static String summary(final ErrorEvent error, final Category category,
                                  final ToolCallRecord previous) {
        final String file = base(error.absolutePath());
        return switch (category) {
            case REPEATED_MISS -> "%s: edit found no match again; the previous miss at seq %d was retried without a read"
                    .formatted(file, previous.seq());
            case MISS_AFTER_EDIT -> "%s: edit found no match; the model's own %s at seq %d had already changed the file"
                    .formatted(file, previous.name(), previous.seq());
            case MISS_AFTER_READ -> "%s: edit found no match although the file was read at seq %d"
                    .formatted(file, previous.seq());
            case MISS_AFTER_PARTIAL_READ -> "%s: edit found no match; the read at seq %d covered only part of the file"
                    .formatted(file, previous.seq());
            default -> "%s: edit found no match; nothing in this stream had read the file".formatted(file);
        };
    }

    private static String base(final String path) {
        if (path == null) {
            return "(no path)";
        }
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
