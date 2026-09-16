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

        final FindingFilters.Sql where =
                new FindingFilters(filter).forFindings(plane, detector, code, session);
        final long total = findingRepository.count(where);
        final List<FindingDto> items = findingRepository.page(where, orderClause(sort), page, size);
        return new FindingsPageDto(total, page, size, items);
    }

    /** Absence is the caller's call: on the wire it is a 404, and only the web layer knows that. */
    public Optional<FindingDetailDto> detail(final long id) {
        return findingRepository.detail(id);
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
