package inspector.insight;

import inspector.dto.FindingDetailDto;
import inspector.dto.FindingDto;
import inspector.dto.FindingsPageDto;
import inspector.query.FindingFilters;
import inspector.query.InsightFilter;
import inspector.query.UnknownFilterValueException;
import inspector.query.Vocabulary;
import inspector.store.FindingRepository;
import inspector.store.VocabularyService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * <p>Sorting is a fixed whitelist. The {@code order by} fragment is assembled only from
 * mapped constants and a lowercased direction, so a SQL-ish sort value is a 400 rather than a
 * second statement, and the stable {@code f.id} tiebreaker keeps pagination deterministic
 * across equal timestamps.
 *
 * <p>The repository hands back rows of the index; turning a row into a finding as a client sees
 * it is this class's job, and it is the only place that knows both names.
 */
@Service
public final class FindingsService {

    /** Fixed ORDER BY whitelist: query value to column. Nothing else reaches the SQL. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "time", "f.occurred_at",
            "plane", "f.plane",
            "detector", "f.detector",
            "code", "f.code",
            "session", "f.session_id",
            "confidence", "f.confidence");

    private static final List<String> SORT_KEYS = List.copyOf(SORT_COLUMNS.keySet());

    /**
     * The largest page a client may ask for. The table renders twenty rows and the UI has no
     * page-size control, so a bigger number is not a request someone can act on: before the cap,
     * {@code ?size=1000000} was answered by materialising a million findings into JSON — the
     * query, the rows and the serialisation all paid, and the browser could not have shown the
     * result either way. 200 is generous next to a corpus measured in hundreds of findings, and
     * above it the answer is the same 400 problem detail every other bad parameter gets, naming
     * the limit. Silently returning 200 of a requested 1,000,000 would describe a page the
     * caller did not ask for.
     */
    private static final int MAX_PAGE_SIZE = 200;

    private final FindingRepository findingRepository;
    private final VocabularyService vocabularyService;

    public FindingsService(
            final FindingRepository findingRepository, final VocabularyService vocabularyService) {
        this.findingRepository = findingRepository;
        this.vocabularyService = vocabularyService;
    }

    public FindingsPageDto page(
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

        final FindingFilters.Sql where =
                new FindingFilters(filter).forFindings(plane, detector, code, session);
        final long total = findingRepository.count(where);
        final List<FindingDto> items =
                findingRepository.page(where, orderClause(sort), page, size).stream()
                        .map(FindingsService::toDto)
                        .toList();
        return new FindingsPageDto(total, page, size, items);
    }

    /** Absence is the caller's call: on the wire it is a 404, and only the web layer knows that. */
    public Optional<FindingDetailDto> detail(final long id) {
        return findingRepository.detail(id).map(FindingsService::toDto);
    }

    /**
     * The row-to-wire mapping, which is the entire reason the two types exist separately: this is
     * where a column of the index becomes a field of the API, and the only place that has to know
     * both names. There is no reflection in it on purpose — a renamed JSON field is one line here
     * and one line in {@code inspector.dto}, and no SQL, no mapper and no test double in between.
     */
    private static FindingDto toDto(final FindingRepository.FindingRow row) {
        return new FindingDto(
                row.id(),
                row.sessionId(),
                row.detector(),
                row.plane(),
                row.category(),
                row.code(),
                row.confidence(),
                row.pathHint(),
                row.seq(),
                row.staleSeq(),
                row.causeSeq(),
                row.occurredAt(),
                row.summary());
    }

    private static FindingDto.Evidence toDto(final FindingRepository.EvidenceRow row) {
        return new FindingDto.Evidence(
                row.seq(), row.verbClass(), row.pathHint(), row.excerptRedacted());
    }

    private static FindingDetailDto toDto(final FindingRepository.FindingDetailRow row) {
        return new FindingDetailDto(
                toDto(row.finding()),
                row.tool(),
                row.evidence().stream().map(FindingsService::toDto).toList());
    }

    /**
     * Parses {@code key[:dir]} against the whitelist. The column and the
     * direction both come from fixed maps; the stable {@code f.id}
     * tiebreaker keeps pagination deterministic.
     *
     * @throws UnknownFilterValueException for an unknown key or direction, carrying the allowed set
     */
    static String orderClause(final String sort) {
        final int separator = sort.indexOf(':');
        final String key = separator < 0 ? sort : sort.substring(0, separator);
        final String direction = separator < 0 ? "desc" : sort.substring(separator + 1);

        final String column = SORT_COLUMNS.get(key);
        if (column == null) {
            throw new UnknownFilterValueException("sort", key, SORT_KEYS);
        }
        final String dir = direction.toLowerCase(Locale.ROOT);
        if (!dir.equals("asc") && !dir.equals("desc")) {
            throw new UnknownFilterValueException("sort", direction, List.of("asc", "desc"));
        }
        return column + " " + dir + ", f.id " + dir;
    }
}
