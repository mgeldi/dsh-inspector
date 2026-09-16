package inspector.api;

import inspector.dto.CohortDto;
import inspector.query.FindingFilters;
import inspector.query.InsightFilter;
import inspector.query.UnknownFilterValueException;
import inspector.query.Vocabulary;
import inspector.store.CohortRepository;
import inspector.store.VocabularyService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cohorts comparison (DESIGN.md §7): one axis at a time, rates per
 * 1,000 <i>observed</i> tool calls, deltas in the same units against the
 * baseline cohort, and a note that states which basis is being shown — a
 * one-row cohort is a description, not a regression analysis, and inferred
 * versions say so.
 *
 * <p>The denominators are observed calls only: a {@code tool/result} whose
 * {@code tool/call} never appeared is stored as a row ({@code outcome_only})
 * so no outcome is lost, but it is not a call, and counting it would bias
 * the rates per convention.
 *
 * <p>{@code groupBy} is a fixed whitelist — the session column comes from
 * a map, so a SQL-ish value is a 400, not a second statement.
 *
 * <p>The endpoint also carries the shared {@link InsightFilter} contract of §7:
 * a cohort rate has to be computed over the population the rail says it is
 * computed over, or the comparison answers a different question than the
 * dashboard above it. Comparing a rollout inside a time window is the point.
 */
@RestController
@RequestMapping("/api/cohorts")
public final class CohortsController {

    /** Fixed GROUP BY whitelist: query value to bare session column (the repository prefixes the alias). */
    private static final Map<String, String> GROUP_COLUMNS = Map.of(
            "harnessVersion", "harness_version",
            "model", "model",
            "schema", "\"schema\"",
            "preset", "agent_preset");

    private static final List<String> GROUP_KEYS = List.copyOf(GROUP_COLUMNS.keySet());

    private final CohortRepository cohortRepository;
    private final VocabularyService vocabularyService;

    public CohortsController(final CohortRepository cohortRepository,
                             final VocabularyService vocabularyService) {
        this.cohortRepository = cohortRepository;
        this.vocabularyService = vocabularyService;
    }

    @GetMapping
    public CohortDto.Page cohorts(
            @ModelAttribute final InsightFilter filter,
            @RequestParam final String groupBy, @RequestParam(required = false) final String baseline) {

        final String axisColumn = GROUP_COLUMNS.get(groupBy);
        if (axisColumn == null) {
            throw new UnknownFilterValueException("groupBy", groupBy, GROUP_KEYS);
        }
        // The same validation every read endpoint runs. A filter value outside the vocabulary
        // is a 400 that names the allowed values — not an empty table that reads as
        // "this cohort has no findings".
        final Vocabulary vocabulary = vocabularyService.vocabulary();
        filter.validate(vocabulary);

        final FindingFilters filters = new FindingFilters(filter);
        final CohortRepository.Result result = cohortRepository.cohorts(axisColumn, filters);
        if (result.cohorts().isEmpty()) {
            return new CohortDto.Page(groupBy, null,
                    filters.isActive() ? "no sessions match the current filters" : "the index is empty",
                    List.of());
        }

        final String baselineKey = baseline != null ? baseline : defaultBaseline(result.cohorts());
        final CohortRepository.Cohort base = result.cohorts().stream()
                .filter(cohort -> cohort.key().equals(baselineKey))
                .findFirst()
                .orElseThrow(() -> new UnknownFilterValueException(
                        "baseline",
                        baseline,
                        result.cohorts().stream().map(CohortRepository.Cohort::key).sorted().toList()));

        final Double baselineRate = rate(base.findings(), base.toolCalls());
        final Double baselineViolation = rate(base.guardFindings(), base.toolCalls());

        final List<CohortDto> rows = new ArrayList<>();
        for (final CohortRepository.Cohort cohort : result.cohorts()) {
            final Double findingsPerK = rate(cohort.findings(), cohort.toolCalls());
            final Double violation = rate(cohort.guardFindings(), cohort.toolCalls());
            rows.add(new CohortDto(
                    cohort.key(),
                    cohort.sessions(),
                    cohort.toolCalls(),
                    cohort.findings(),
                    cohort.guardFindings(),
                    findingsPerK,
                    violation,
                    delta(findingsPerK, baselineRate),
                    delta(violation, baselineViolation)));
        }

        return new CohortDto.Page(groupBy, baselineKey,
                basisNote(groupBy, baseline, result, baselineKey, filters.isActive()), rows);
    }

    /** Default baseline: highest tool-call count, key ascending on ties. */
    private static String defaultBaseline(final List<CohortRepository.Cohort> cohorts) {
        return cohorts.stream()
                .min(Comparator.comparingLong(CohortRepository.Cohort::toolCalls)
                        .reversed()
                        .thenComparing(CohortRepository.Cohort::key))
                .map(CohortRepository.Cohort::key)
                .orElseThrow(() -> new IllegalStateException("empty cohort table has no baseline"));
    }

    /**
     * The screen states which basis it is showing (DESIGN.md §7).
     */
    private static String basisNote(
            final String groupBy,
            final String requestedBaseline,
            final CohortRepository.Result result,
            final String baselineKey,
            final boolean filtered) {
        final List<String> notes = new ArrayList<>();
        if (filtered) {
            // The screen says so whenever the numbers are a subset: a rate that happens to be
            // filtered and a rate that was asked to be filtered look identical otherwise, and
            // a screenshot of the former gets forwarded as evidence for the latter.
            notes.add("shared filters are active, every rate below is for the filtered subset");
        }
        if (result.cohorts().size() == 1) {
            notes.add("one-row cohort: " + groupBy + " is single-valued in "
                    + (filtered ? "this selection" : "this index") + ", so this is a description, "
                    + "not a comparison");
        }
        if (result.allVersionInferred()) {
            notes.add("harness_version is inferred for every session (version_inferred=1), not declared by the "
                    + "harness");
        }
        if (requestedBaseline == null) {
            notes.add("baseline " + baselineKey + " chosen by highest tool-call count");
        }
        return notes.isEmpty() ? null : String.join("; ", notes);
    }

    /**
     * Findings per 1,000 observed tool calls, rounded to two decimals. Null
     * when the cohort has no observed tool calls — never a division by zero.
     */
    static Double rate(final long numerator, final long toolCalls) {
        return toolCalls == 0 ? null : round2(numerator * 1000.0 / toolCalls);
    }

    /**
     * Delta in the same units as the rate (points on the per-1,000 scale).
     * Null when either side has no tool calls.
     */
    static Double delta(final Double value, final Double baseline) {
        return value == null || baseline == null ? null : round2(value - baseline);
    }

    private static double round2(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
