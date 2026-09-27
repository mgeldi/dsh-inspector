package inspector.dto;

import java.util.List;

/**
 * One cohort in the comparison table (DESIGN.md §7).
 *
 * <p>Rates are findings per 1,000 <i>observed</i> tool calls;
 * {@code violationRatePerK} is the GUARD-plane share of the same. Deltas are
 * in the same units against the baseline cohort. A cohort with no observed
 * tool calls has no rate — null, never 0/0. The denominators exclude
 * outcome-only rows: a {@code tool/result} whose {@code tool/call} never
 * appeared is stored but is not a call.
 *
 * @param key                 the cohort key (NULL axes read "unknown")
 * @param sessions            sessions in the cohort
 * @param toolCalls           observed tool calls in the cohort (outcome_only = 0)
 * @param findings            findings in the cohort
 * @param guardFindings       GUARD-plane findings in the cohort
 * @param findingsPerKCalls   findings per 1,000 observed tool calls, null if no observed calls
 * @param violationRatePerK   GUARD findings per 1,000 observed tool calls, null if no observed calls
 * @param findingsPerKCallsDelta    rate minus the baseline rate, in the same units
 * @param violationRatePerKDelta    violation rate minus the baseline, in the same units
 * @param misuseFindings      MODEL_MISUSE-plane findings in the cohort
 * @param infraFindings       INFRASTRUCTURE-plane findings in the cohort
 * @param misuseRatePerK      MODEL_MISUSE findings per 1,000 observed tool calls
 * @param infraRatePerK       INFRASTRUCTURE findings per 1,000 observed tool calls
 * @param misuseRatePerKDelta misuse rate minus the baseline's
 * @param infraRatePerKDelta  infrastructure rate minus the baseline's
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
        Double violationRatePerKDelta,
        long misuseFindings,
        long infraFindings,
        Double misuseRatePerK,
        Double infraRatePerK,
        Double misuseRatePerKDelta,
        Double infraRatePerKDelta) {

    /**
     * The whole cohorts screen: the axis, the chosen baseline, the note that
     * states which basis is being shown (one-row cohorts, inferred versions),
     * and the rows.
     */
    public record Page(String groupBy, String baseline, String basisNote, List<CohortDto> cohorts) {
    }
}
