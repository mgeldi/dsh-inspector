package inspector.insight;

import inspector.dto.FindingContextDto;
import inspector.dto.FindingDetailDto;
import inspector.dto.FindingDto;
import inspector.dto.FindingsPageDto;
import inspector.query.FindingsQuery;
import inspector.query.InsightFilter;
import inspector.query.UnknownFilterValueException;
import inspector.query.Vocabulary;
import inspector.store.FindingRepository;
import inspector.store.ToolCallRepository;
import inspector.store.VocabularyService;
import inspector.store.entity.FindingEntity;
import inspector.store.entity.ShellEvidenceEntity;
import inspector.store.entity.ToolCallEntity;
import inspector.store.entity.ToolCallId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The findings table (DESIGN.md §7) and the detail lookup that is the only reader allowed to
 * return evidence text (§4.1).
 *
 * <p>The shared {@link InsightFilter} values are validated against the vocabulary first, and
 * the findings-axis filters (plane, detector, session, code) go through the same check: a
 * value that is not in the index is a 400 naming the allowed set, never an empty table that
 * reads as "nothing matched". Validation is a precondition of answering the question, so it
 * belongs here and not in the parameter binding.
 *
 * <p>Sorting is a fixed whitelist. The {@link Sort} is built only from mapped entity attributes
 * and a direction checked against two values, so a SQL-ish sort value is a 400 rather than
 * anything the query sees, and the stable {@code id} tiebreaker keeps pagination deterministic
 * across equal timestamps. Both halves of {@code key:direction} are matched case-insensitively: a
 * whitelist key is a constant, not data, so folding it cannot widen a filter the way folding a
 * vocabulary value would.
 *
 * <p>The repository hands back rows of the index; turning a row into a finding as a client sees
 * it is this class's job, and it is the only place that knows both names.
 */
@Service
public final class FindingsService {

    /** Fixed ORDER BY whitelist: query value to entity attribute. Nothing else reaches the query. */
    private static final Map<String, String> SORT_ATTRIBUTES = Map.of(
            "time", "occurredAt",
            "plane", "plane",
            "detector", "detector",
            "code", "code",
            "session", "sessionId",
            "confidence", "confidence");

    /**
     * The keys a caller may use, in the order a person should read them — this is the list a 400
     * prints. Not derived from {@link #SORT_ATTRIBUTES}: a {@code Map.of} keySet has no defined
     * iteration order, so the error message someone sees when they mistype would be chosen by a
     * hash, and free to rearrange when an unrelated key is added.
     */
    private static final List<String> SORT_KEYS = List.of(
            "time", "plane", "detector", "code", "session", "confidence");

    /**
     * The largest page a client may ask for. The table renders twenty rows, so a bigger number is
     * not a request someone can act on: before the cap, {@code ?size=1000000} was answered by
     * materialising a million findings into JSON. 200 is generous next to a corpus measured in
     * hundreds of findings, and above it the answer is the same 400 every other bad parameter
     * gets, naming the limit.
     */
    private static final int MAX_PAGE_SIZE = 200;

    private final FindingRepository findingRepository;
    private final ToolCallRepository toolCallRepository;
    private final VocabularyService vocabularyService;
    private final ReadSnapshot snapshot;

    public FindingsService(final FindingRepository findingRepository,
                           final ToolCallRepository toolCallRepository,
                           final VocabularyService vocabularyService,
                           final ReadSnapshot snapshot) {
        this.findingRepository = findingRepository;
        this.toolCallRepository = toolCallRepository;
        this.vocabularyService = vocabularyService;
        this.snapshot = snapshot;
    }

    /** One page and its total, from one snapshot of the index (ReadSnapshot). */
    public FindingsPageDto page(
            final InsightFilter filter,
            final String plane,
            final String detector,
            final String session,
            final String code,
            final String sort,
            final int page,
            final int size) {
        return snapshot.read(() -> pageInSnapshot(filter, plane, detector, session, code, sort, page, size));
    }

    private FindingsPageDto pageInSnapshot(
            final InsightFilter filter,
            final String plane,
            final String detector,
            final String session,
            final String code,
            final String sort,
            final int page,
            final int size) {

        final Vocabulary vocabulary = vocabularyService.vocabulary();
        filter.validate(vocabulary);
        InsightFilter.requireKnown(vocabulary, "plane", plane);
        InsightFilter.requireKnown(vocabulary, "detector", detector);
        InsightFilter.requireKnown(vocabulary, "session", session);
        InsightFilter.requireKnown(vocabulary, "code", code);
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0, was " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0, was " + size);
        }
        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be <= " + MAX_PAGE_SIZE + ", was " + size);
        }

        final Page<FindingEntity> rows = findingRepository.page(
                new FindingsQuery(filter, plane, detector, code, session),
                PageRequest.of(page, size, sortOf(sort)));
        return new FindingsPageDto(rows.getTotalElements(), page, size,
                rows.getContent().stream().map(FindingsService::toDto).toList());
    }

    /** Absence is the caller's call: on the wire it is a 404, and only the web layer knows that. */
    public Optional<FindingDetailDto> detail(final long id) {
        return snapshot.read(() -> detailInSnapshot(id));
    }

    private Optional<FindingDetailDto> detailInSnapshot(final long id) {
        return findingRepository.findDetail(id).map(finding -> new FindingDetailDto(
                toDto(finding),
                finding.getSeq() == null ? null : toolCallRepository.nameAt(
                        new ToolCallId(finding.getSessionId(), finding.getSourceFile(), finding.getSeq()))
                        .orElse(null),
                finding.getEvidence().stream().map(FindingsService::toDto).toList()));
    }

    /** The widest window either side of a finding: past it, a sequence stops being a neighbourhood. */
    static final int MAX_CONTEXT = 50;

    /**
     * The tool calls around one finding in its own stream — {@code window} before (the finding's
     * call included) and {@code window} after — and the findings among them. Structure only.
     *
     * @throws IllegalArgumentException for a window outside 1..{@value #MAX_CONTEXT}
     */
    public Optional<FindingContextDto> context(final long id, final int window) {
        if (window < 1 || window > MAX_CONTEXT) {
            throw new IllegalArgumentException("window must be between 1 and " + MAX_CONTEXT + ", was " + window);
        }
        return snapshot.read(() -> contextInSnapshot(id, window));
    }

    private Optional<FindingContextDto> contextInSnapshot(final long id, final int window) {
        return findingRepository.findById(id).map(finding -> {
            final String sid = finding.getSessionId();
            final String file = finding.getSourceFile();
            final Integer anchor = finding.getSeq() != null ? finding.getSeq()
                    : toolCallRepository.lastSeqStartedBy(sid, file, finding.getOccurredAt()).orElse(null);
            if (anchor == null) {
                return new FindingContextDto(id, null, List.of(), List.of(neighbour(finding)));
            }
            final List<ToolCallEntity> calls = new ArrayList<>(
                    toolCallRepository.upTo(sid, file, anchor, PageRequest.of(0, window + 1)));
            calls.addAll(toolCallRepository.after(sid, file, anchor, PageRequest.of(0, window)));
            calls.sort(Comparator.comparingInt(c -> c.getId().seq()));
            final int from = calls.isEmpty() ? anchor : calls.getFirst().getId().seq();
            final int to = calls.isEmpty() ? anchor : calls.getLast().getId().seq();
            // The finding the sequence is centred on is always among its neighbours, first when it has
            // no seq of its own — a fatal turn's window holds calls, not the turn.
            final List<FindingContextDto.Neighbour> neighbours = new ArrayList<>(
                    findingRepository.inStreamBetween(sid, file, from, to).stream()
                            .map(FindingsService::neighbour).toList());
            if (neighbours.stream().noneMatch(n -> n.id() == id)) {
                neighbours.addFirst(neighbour(finding));
            }
            return new FindingContextDto(id, anchor,
                    calls.stream().map(c -> call(c, finding)).toList(), List.copyOf(neighbours));
        });
    }

    private static FindingContextDto.Call call(final ToolCallEntity c, final FindingEntity finding) {
        final int seq = c.getId().seq();
        final String mark = finding.getSeq() != null && seq == finding.getSeq() ? "finding"
                : finding.getCauseSeq() != null && seq == finding.getCauseSeq() ? "cause"
                : finding.getStaleSeq() != null && seq == finding.getStaleSeq() ? "stale" : null;
        return new FindingContextDto.Call(seq, c.getName(), c.getErrorCode(), c.getPlane(), c.getPathHint(),
                c.getDurationMs(), c.getStartedAt(), mark);
    }

    private static FindingContextDto.Neighbour neighbour(final FindingEntity f) {
        return new FindingContextDto.Neighbour(f.getId(), f.getDetector(), f.getCode(), f.getCategory(),
                f.getSeq() == null ? null : f.getSeq().longValue());
    }

    /**
     * The entity-to-wire mapping, which is the entire reason the two types exist separately: this
     * is where a column of the index becomes a field of the API, and the only place that has to
     * know both names. A renamed JSON field is one line here and one line in {@code inspector.dto}.
     */
    static FindingDto toDto(final FindingEntity row) {
        return new FindingDto(
                row.getId(),
                row.getSessionId(),
                row.getDetector(),
                row.getPlane(),
                row.getCategory(),
                row.getCode(),
                row.getDetail(),
                row.getConfidence(),
                row.getPathHint(),
                row.getSeq() == null ? null : row.getSeq().longValue(),
                row.getStaleSeq() == null ? null : row.getStaleSeq().longValue(),
                row.getCauseSeq() == null ? null : row.getCauseSeq().longValue(),
                row.getOccurredAt(),
                row.getSummary());
    }

    private static FindingDto.Evidence toDto(final ShellEvidenceEntity row) {
        return new FindingDto.Evidence(
                row.getId().seq(), row.getVerbClass(), row.getPathHint(), row.getExcerptRedacted());
    }

    /**
     * Parses {@code key[:dir]} against the whitelist. Both halves of the token are matched
     * case-insensitively, because one syntax carrying two tolerances is a trap: {@code time:DESC}
     * was accepted while {@code TIME:desc} was a 400. The 400 quotes the value as it was sent.
     *
     * <p>What stays strict is the part that keeps the query closed: the attribute comes from a
     * fixed map and the direction from a two-value check. The stable {@code id} tiebreaker keeps
     * pagination deterministic.
     *
     * @throws UnknownFilterValueException for an unknown key or direction, carrying the allowed set
     */
    static Sort sortOf(final String sort) {
        final int separator = sort.indexOf(':');
        final String key = separator < 0 ? sort : sort.substring(0, separator);
        final String direction = separator < 0 ? "desc" : sort.substring(separator + 1);

        final String attribute = SORT_ATTRIBUTES.get(key.toLowerCase(Locale.ROOT));
        if (attribute == null) {
            throw new UnknownFilterValueException("sort", key, SORT_KEYS);
        }
        final String dir = direction.toLowerCase(Locale.ROOT);
        if (!dir.equals("asc") && !dir.equals("desc")) {
            throw new UnknownFilterValueException("sort", direction, List.of("asc", "desc"));
        }
        final Sort.Direction d = dir.equals("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(d, attribute).and(Sort.by(d, "id"));
    }
}
