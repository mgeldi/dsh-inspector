package inspector.api.dto;

import java.util.List;

/**
 * One cohort in the comparison table (DESIGN.md §7).
 *
 * <p>Rates are findings per 1,000 tool calls; {@code violationRatePerK} is the
 * GUARD-plane share of the same. Deltas are in the same units against the
 * baseline cohort. A cohort with no tool calls has no rate — null, never
 * 0/0.
 *
 * @param key                 the cohort key (NULL axes read "unknown")
 * @param sessions            sessions in the cohort
 * @param toolCalls           tool calls in the cohort
 * @param findings            findings in the cohort
 * @param guardFindings       GUARD-plane findings in the cohort
 * @param findingsPerKCalls   findings per 1,000 tool calls, null if no calls
 * @param violationRatePerK   GUARD findings per 1,000 tool calls, null if no calls
 * @param findingsPerKCallsDelta    rate minus the baseline rate, in the same units
 * @param violationRatePerKDelta    violation rate minus the baseline, in the same units
 */
public record CohortDto(
        String key,
        long sessions,
        long toolCalls,
        long findings,
        long guardFindings,
        Double findingsPerKCalls,
        Double violationRatePerK,
        Double findingsPerKCallsDelta,
        Double violationRatePerKDelta) {

    /**
     * The whole cohorts screen: the axis, the chosen baseline, the note that
     * states which basis is being shown (one-row cohorts, inferred versions),
     * and the rows.
     */
    public record Page(String groupBy, String baseline, String basisNote, List<CohortDto> cohorts) {
    }
}
