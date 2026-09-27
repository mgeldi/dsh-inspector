package inspector.insight;

/**
 * Whether a candidate cohort's rate differs from a baseline's by more than chance, for counts of
 * findings over counts of observed tool calls.
 *
 * <p>The comparison is the rate ratio {@code (x₂/n₂) / (x₁/n₁)} with a 95% interval on its log,
 * {@code log RR ± q·√(1/x₁ + 1/x₂)} — the standard interval for two Poisson rates, where q is 1.96
 * for independent events.
 * A zero count has no log, so both counts get the Haldane correction of ½ when either is zero; the
 * interval that comes out is wide, which is the honest answer to "one side saw nothing".
 *
 * <p>Failures are not independent: they cluster in sessions — one burst of blind edit retries is a
 * dozen findings from one conversation. A plain Poisson interval treats every call as its own coin
 * and comes out too narrow, which turns one bad afternoon into a "significant" regression. So the
 * standard error is widened by the square root of a dispersion factor φ, estimated from how unevenly
 * the events fall across the sessions of each side and never below 1: evenly spread failures leave
 * the interval as it is, concentrated ones widen it. That φ is itself estimated from a handful of
 * sessions, so q is the t quantile on the degrees of freedom those sessions carry rather than the
 * normal 1.96 — with six sessions a side the normal quantile called a difference that was not there
 * two to three times as often as the nominal 5%, and with one contributing session nothing can be
 * decided at all.
 *
 * <p>The verdict reads the interval, never the point estimate: {@code better} only when the whole
 * interval lies below 1, {@code worse} only when it lies above, {@code inconclusive} otherwise.
 * That is the whole reason this class exists. A harness change judged by a raw count, or by a rate
 * without an interval, will be "confirmed" by noise about as often as by effect — at the rates
 * this corpus shows (a few per thousand calls) a week of use on each side is often not enough, and
 * the judge has to be able to say so.
 */
public final class RateRatio {

    /** Two-sided 95%. */
    private static final double Z = 1.959963984540054;

    /** The two-sided 95% t quantile for 1 to 30 degrees of freedom; index 0 is df 1. */
    private static final double[] T_975 = {
            12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
            2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};

    public enum Verdict { BETTER, WORSE, INCONCLUSIVE, NO_DATA }

    /**
     * @param ratio candidate rate over baseline rate; null when either side has no calls
     * @param low   lower end of the 95% interval
     * @param high  upper end of the 95% interval
     */
    public record Comparison(Double ratio, Double low, Double high, Verdict verdict) {
    }

    private RateRatio() {
    }

    /**
     * @param baselineCount     findings in the baseline cohort
     * @param baselineCalls     observed tool calls in the baseline cohort
     * @param candidateCount    findings in the candidate cohort
     * @param candidateCalls    observed tool calls in the candidate cohort
     */
    public static Comparison compare(final long baselineCount, final long baselineCalls,
                                     final long candidateCount, final long candidateCalls) {
        return compare(baselineCount, baselineCalls, candidateCount, candidateCalls, 1.0);
    }

    /**
     * The same comparison with the standard error widened by {@code √dispersion}, on the normal
     * quantile.
     *
     * @param dispersion the variance inflation φ (see {@link #dispersion}); values below 1 are treated as 1
     */
    public static Comparison compare(final long baselineCount, final long baselineCalls,
                                     final long candidateCount, final long candidateCalls,
                                     final double dispersion) {
        return compare(baselineCount, baselineCalls, candidateCount, candidateCalls, dispersion,
                Double.POSITIVE_INFINITY);
    }

    /**
     * The same comparison on the t quantile for {@code degreesOfFreedom}, for when φ was estimated
     * from few sessions. Fractional degrees of freedom round down. Below 1 there is nothing to
     * estimate a spread from — the t quantile goes to infinity — so the ratio comes back with no
     * interval, and inconclusive.
     *
     * @param dispersion       the variance inflation φ; values below 1 are treated as 1
     * @param degreesOfFreedom what the variance estimate rests on; infinite for the normal quantile
     */
    public static Comparison compare(final long baselineCount, final long baselineCalls,
                                     final long candidateCount, final long candidateCalls,
                                     final double dispersion, final double degreesOfFreedom) {
        if (baselineCalls <= 0 || candidateCalls <= 0) {
            return new Comparison(null, null, null, Verdict.NO_DATA);
        }
        if (baselineCount == 0 && candidateCount == 0) {
            // Nothing happened on either side: no rate to compare, and no evidence of a change.
            return new Comparison(null, null, null, Verdict.INCONCLUSIVE);
        }
        final double correction = baselineCount == 0 || candidateCount == 0 ? 0.5 : 0.0;
        final double x1 = baselineCount + correction;
        final double x2 = candidateCount + correction;
        final double logRatio = Math.log((x2 / candidateCalls) / (x1 / baselineCalls));
        final double se = Math.sqrt(Math.max(1.0, dispersion) * (1.0 / x1 + 1.0 / x2));
        if (degreesOfFreedom < 1.0) {
            return new Comparison(round3(Math.exp(logRatio)), null, null, Verdict.INCONCLUSIVE);
        }
        final double q = tQuantile(degreesOfFreedom);
        final double low = Math.exp(logRatio - q * se);
        final double high = Math.exp(logRatio + q * se);
        final Verdict verdict = high < 1.0 ? Verdict.BETTER : low > 1.0 ? Verdict.WORSE : Verdict.INCONCLUSIVE;
        return new Comparison(round3(Math.exp(logRatio)), round3(low), round3(high), verdict);
    }

    /**
     * How clustered one side's events are, as the factor by which the variance of its total exceeds
     * the Poisson variance: the cluster-robust ("sandwich") estimate with sessions as clusters,
     * {@code k/(k−1) · Σ (xᵢ − r·nᵢ)² / X}, where r = X/N is the side's pooled rate, X its events and
     * N its calls. A session contributes its squared residual whatever its exposure — so a session
     * with events but no observed call (a turn that died before its first tool call) counts, and a
     * lone such event adds about what Poisson expects of one event, while a burst of ten in one
     * session adds a hundred. An earlier form (Pearson's, with a one-call exposure given to such
     * sessions) divided a lone fatal turn by a near-zero expectation and let one isolated event read
     * as extreme clustering. Returns 1 with fewer than two sessions or no events — the plain interval —
     * and when no session holds more than one event: a sum of independent 0/1 outcomes varies less
     * than its mean, so Poisson already bounds it, and what the residuals would measure there is only
     * that events land in sessions with few calls (a fatal turn needs none).
     *
     * @param counts per-session event counts
     * @param calls  per-session observed calls, index-aligned with {@code counts}; zero allowed
     */
    public static double dispersion(final long[] counts, final long[] calls) {
        long x = 0;
        long n = 0;
        for (int i = 0; i < counts.length; i++) {
            x += counts[i];
            n += calls[i];
        }
        final int k = counts.length;
        if (k < 2 || x == 0 || java.util.Arrays.stream(counts).max().orElse(0) <= 1) {
            return 1.0;
        }
        final double rate = n == 0 ? 0.0 : (double) x / n;
        double squares = 0;
        for (int i = 0; i < k; i++) {
            final double residual = counts[i] - rate * calls[i];
            squares += residual * residual;
        }
        return Math.max(1.0, (double) k / (k - 1) * squares / x);
    }

    /**
     * The two-sided 95% quantile of Student's t: the table to 30 degrees of freedom, the
     * Cornish–Fisher expansion around the normal quantile beyond (within 0.001 of the exact value at
     * 30 and closer above), the normal quantile at infinity. Rounds a fractional df down, which only
     * ever widens the interval.
     */
    static double tQuantile(final double degreesOfFreedom) {
        if (Double.isInfinite(degreesOfFreedom) || Double.isNaN(degreesOfFreedom)) {
            return Z;
        }
        final int df = (int) Math.max(1, Math.floor(Math.min(degreesOfFreedom, 1e6)));
        if (df <= T_975.length) {
            return T_975[df - 1];
        }
        final double z = Z;
        final double z3 = z * z * z;
        final double z5 = z3 * z * z;
        final double z7 = z5 * z * z;
        return z + (z3 + z) / (4.0 * df)
                + (5 * z5 + 16 * z3 + 3 * z) / (96.0 * df * df)
                + (3 * z7 + 19 * z5 + 17 * z3 - 15 * z) / (384.0 * df * df * df);
    }

    private static double round3(final double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
