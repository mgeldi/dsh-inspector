package inspector.api;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The findings table and the one detail endpoint that returns evidence
 * text (DESIGN.md §7, §4.1).
 *
 * <p>The shared {@link InsightFilter} values are validated against the
 * vocabulary first; the findings-axis filters (plane, detector, session,
 * code) go through the same check. Sorting is a fixed whitelist — the
 * {@code order by} fragment is assembled only from mapped constants, so a
 * SQL-ish sort value is a 400, not a second statement.
 */
@RestController
@RequestMapping("/api/findings")
public final class FindingsController {

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

    public FindingsController(
            final FindingRepository findingRepository, final VocabularyService vocabularyService) {
        this.findingRepository = findingRepository;
        this.vocabularyService = vocabularyService;
    }

    @GetMapping
    public FindingsPageDto page(
            @ModelAttribute final InsightFilter filter,
            @RequestParam(required = false) final String plane,
            @RequestParam(required = false) final String detector,
            @RequestParam(required = false) final String session,
            @RequestParam(required = false) final String code,
            @RequestParam(defaultValue = "time:desc") final String sort,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size) {

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

    /** The single endpoint allowed to return evidence text (DESIGN.md §4.1). */
    @GetMapping("/{id}")
    public FindingDetailDto detail(@PathVariable final long id) {
        return findingRepository.detail(id).orElseThrow(() -> new FindingNotFoundException(id));
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
