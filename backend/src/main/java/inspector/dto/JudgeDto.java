package inspector.dto;

import java.util.List;

/**
 * A two-cohort comparison with an interval behind every verdict (DESIGN.md §7a).
 *
 * <p>Each row compares one numerator — all findings, one plane, or one code (a codeless finding
 * is keyed by its detector) — over the same denominator, observed tool calls. {@code rateRatio} is
 * candidate over baseline; {@code ratioLow}/{@code ratioHigh} its 95% interval; {@code verdict}
 * is {@code better} or {@code worse} only when the whole interval is on one side of 1, and
 * {@code inconclusive} otherwise. Lower is better throughout: every numerator is a failure.
 *
 * @param groupBy         the axis the two cohorts are values of
 * @param baseline        the cohort compared against
 * @param candidate       the cohort being judged
 * @param basisNote       what the numbers are and are not, in a sentence; null when nothing to say
 * @param baselineCohort  the baseline's size
 * @param candidateCohort the candidate's size
 * @param rows            total, then the three planes, then every code either side has, busiest first
 */
public record JudgeDto(String groupBy, String baseline, String candidate, String basisNote,
                       Side baselineCohort, Side candidateCohort, List<Row> rows) {

    /** How much evidence one side stands on. */
    public record Side(String key, long sessions, long toolCalls) {
    }

    /**
     * @param scope          {@code total}, {@code plane} or {@code code}
     * @param key            the plane or code; {@code all} for the total
     * @param baselineCount  findings on the baseline side
     * @param candidateCount findings on the candidate side
     * @param baselinePerK   baseline rate per 1,000 observed calls, null with no calls
     * @param candidatePerK  candidate rate per 1,000 observed calls, null with no calls
     * @param rateRatio      candidate rate over baseline rate, null when there is nothing to divide
     * @param ratioLow       lower end of the 95% interval
     * @param ratioHigh      upper end of the 95% interval
     * @param verdict        {@code better}, {@code worse}, {@code inconclusive} or {@code no-data}
     * @param baselineSessions  sessions on the baseline side that contributed at least one finding
     * @param candidateSessions the same on the candidate side — 44 misses from one conversation and
     *                          44 from forty are different evidence, and the interval knows it
     * @param dispersion     the φ the interval was widened by — both sides' cluster-robust factors,
     *                       each weighted by its own count; 1 when the findings are spread as evenly
     *                       across sessions as chance would spread them
     * @param degreesOfFreedom the Welch–Satterthwaite df of the interval's t quantile — what the
     *                       sessions that contributed can support. Below 1 (a burst in a single
     *                       session, or a cohort of one session) there is no interval at all and
     *                       {@code ratioLow}/{@code ratioHigh} are null. Null when nothing beyond
     *                       Poisson was estimated (the plain normal interval) or there is no ratio
     */
    public record Row(String scope, String key, long baselineCount, long candidateCount,
                      Double baselinePerK, Double candidatePerK, Double rateRatio,
                      Double ratioLow, Double ratioHigh, String verdict,
                      long baselineSessions, long candidateSessions, double dispersion,
                      Double degreesOfFreedom) {
    }
}
