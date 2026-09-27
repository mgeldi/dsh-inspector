package inspector.insight;

import inspector.dto.JudgeDto;
import inspector.query.InsightFilter;
import inspector.query.UnknownFilterValueException;
import inspector.store.CohortRepository;
import inspector.store.VocabularyService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * The judge a harness-improvement loop needs: did the candidate cohort get better or worse than
 * the baseline, per failure, by more than chance? (DESIGN.md §7a)
 *
 * <p>The cohorts screen answers "what are the rates"; this answers "is the difference real". It
 * takes the same axes and the same shared filter as {@link CohortService}, so a comparison inside a
 * time window or for one role is the same selection the rest of the dashboard describes — and
 * comparing the orchestrator's rates before and after an instruction change is
 * {@code ?role=orchestrator&groupBy=harnessVersion}.
 *
 * <p>Every verdict comes from {@link RateRatio}: an interval, never a point estimate. A loop that
 * keeps whatever change happened to precede a lucky week is optimising noise.
 */
@Service
public final class JudgeService {

    private static final List<String> PLANES = List.of("GUARD", "MODEL_MISUSE", "INFRASTRUCTURE");

    private final CohortRepository cohortRepository;
    private final VocabularyService vocabularyService;
    private final ReadSnapshot snapshot;

    public JudgeService(final CohortRepository cohortRepository, final VocabularyService vocabularyService,
                        final ReadSnapshot snapshot) {
        this.cohortRepository = cohortRepository;
        this.vocabularyService = vocabularyService;
        this.snapshot = snapshot;
    }

    /**
     * @param baseline  cohort to compare against; defaults to the one with the most tool calls
     * @param candidate cohort to judge; may be omitted only when the axis has exactly two cohorts
     */
    public JudgeDto judge(final InsightFilter filter, final String groupBy, final String baseline,
                          final String candidate) {
        // One snapshot: the judge reads the cohort totals, the codes and two per-session tables, and
        // a verdict assembled from before and after a re-index would compare numbers that never
        // coexisted (ReadSnapshot says how).
        return snapshot.read(() -> judgeInSnapshot(filter, groupBy, baseline, candidate));
    }

    private JudgeDto judgeInSnapshot(final InsightFilter filter, final String groupBy, final String baseline,
                                     final String candidate) {
        final String axis = CohortService.GROUP_AXES.get(groupBy);
        if (axis == null) {
            throw new UnknownFilterValueException("groupBy", groupBy, CohortService.GROUP_KEYS);
        }
        filter.validate(vocabularyService.vocabulary());

        final CohortRepository.Result result = cohortRepository.cohorts(axis, filter);
        final List<String> keys = result.cohorts().stream().map(CohortRepository.Cohort::key).sorted().toList();
        final String baseKey = baseline != null ? baseline
                : result.cohorts().isEmpty() ? null : CohortService.defaultBaseline(result.cohorts());
        final CohortRepository.Cohort base = find(result, baseKey)
                .orElseThrow(() -> new UnknownFilterValueException("baseline", String.valueOf(baseline), keys));
        final String candidateKey = candidate != null ? candidate : onlyOther(keys, base.key());
        final CohortRepository.Cohort cand = find(result, candidateKey)
                .orElseThrow(() -> new UnknownFilterValueException("candidate", String.valueOf(candidate),
                        keys.stream().filter(k -> !k.equals(base.key())).toList()));
        if (cand.key().equals(base.key())) {
            throw new IllegalArgumentException("candidate and baseline are the same cohort ("
                    + base.key() + "); a cohort compared with itself has nothing to judge");
        }

        final List<CohortRepository.SessionCalls> calls = cohortRepository.callsPerSession(axis, filter);
        final List<CohortRepository.SessionFindings> found = cohortRepository.findingsPerSession(axis, filter);
        final Sessions baseSessions = new Sessions(base.key(), calls, found);
        final Sessions candSessions = new Sessions(cand.key(), calls, found);
        final List<JudgeDto.Row> rows = new ArrayList<>();
        rows.add(row("total", "all", base, cand, baseSessions, candSessions, f -> true));
        for (final String plane : PLANES) {
            rows.add(row("plane", plane, base, cand, baseSessions, candSessions, f -> plane.equals(f.plane())));
        }
        rows.addAll(codeRows(axis, filter, base, cand, baseSessions, candSessions));

        return new JudgeDto(groupBy, base.key(), cand.key(),
                basisNote(filter, result.allVersionInferred(), baseline == null, groupBy),
                new JudgeDto.Side(base.key(), base.sessions(), base.toolCalls()),
                new JudgeDto.Side(cand.key(), cand.sessions(), cand.toolCalls()),
                List.copyOf(rows));
    }

    private List<JudgeDto.Row> codeRows(final String axis, final InsightFilter filter,
                                        final CohortRepository.Cohort base, final CohortRepository.Cohort cand,
                                        final Sessions baseSessions, final Sessions candSessions) {
        final Map<String, long[]> byCode = new HashMap<>();
        for (final CohortRepository.CodeCount count : cohortRepository.codeCounts(axis, filter)) {
            final int side = count.key().equals(base.key()) ? 0 : count.key().equals(cand.key()) ? 1 : -1;
            if (side >= 0) {
                byCode.computeIfAbsent(count.code(), k -> new long[2])[side] += count.count();
            }
        }
        return new TreeSet<>(byCode.keySet()).stream()
                .sorted(Comparator.comparingLong((String code) -> -(byCode.get(code)[0] + byCode.get(code)[1]))
                        .thenComparing(Comparator.naturalOrder()))
                .map(code -> row("code", code, base, cand, baseSessions, candSessions, f -> code.equals(f.code())))
                .toList();
    }

    /**
     * One row: the counts, the session counts and each side's dispersion come from the per-session
     * tables, the calls from the cohort totals; the two sides' φ and the interval's degrees of
     * freedom come from {@link #spread}.
     */
    private static JudgeDto.Row row(final String scope, final String key,
                                    final CohortRepository.Cohort base, final CohortRepository.Cohort cand,
                                    final Sessions baseSessions, final Sessions candSessions,
                                    final Predicate<CohortRepository.SessionFindings> which) {
        final long baseCount = baseSessions.total(which);
        final long candCount = candSessions.total(which);
        final double phiBase = baseSessions.dispersion(which);
        final double phiCand = candSessions.dispersion(which);
        final long baseContributing = baseSessions.contributing(which);
        final long candContributing = candSessions.contributing(which);
        final Spread spread = spread(new Side(phiBase, baseCount, baseContributing, baseSessions.units().size()),
                new Side(phiCand, candCount, candContributing, candSessions.units().size()));
        final double phi = spread.phi();
        final double df = spread.degreesOfFreedom();
        final RateRatio.Comparison c = RateRatio.compare(baseCount, base.toolCalls(), candCount, cand.toolCalls(),
                phi, df);
        return new JudgeDto.Row(scope, key, baseCount, candCount,
                CohortService.rate(baseCount, base.toolCalls()), CohortService.rate(candCount, cand.toolCalls()),
                c.ratio(), c.low(), c.high(), c.verdict().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                baseContributing, candContributing,
                Math.round(phi * 100.0) / 100.0,
                c.ratio() == null || Double.isInfinite(df) ? null : Math.round(df * 10.0) / 10.0);
    }

    /** What one side brings to the interval: its φ, its findings, the sessions they came from. */
    record Side(double phi, long count, long contributing, long units) {

        /** Whether the side can show how its own findings spread: some, in two sessions or more. */
        boolean showsSpread() {
            return count > 0 && contributing >= 2;
        }
    }

    /** The factor the interval's variance is widened by, and the degrees of freedom behind it. */
    record Spread(double phi, double degreesOfFreedom) {
    }

    /**
     * How wide the interval has to be, from how both sides' findings fall across their sessions.
     *
     * <p><b>φ.</b> The log ratio's variance is {@code φ₁/x₁ + φ₂/x₂}; the single factor on
     * {@code 1/x₁ + 1/x₂} that reproduces it is the count-weighted mean, so an evenly spread side
     * does not dilute a clustered one. A side that cannot show its own spread — no findings, or all of
     * them in one session — is taken to spread at least as the other side does: under "no
     * difference" both come from one process, and giving such a side the Poisson 1 let a zero side
     * decide against a burst spread over two or three sessions. At least, because a one-session
     * side's own φ still says it is a burst — thirty findings in one conversation keep their φ.
     *
     * <p><b>Degrees of freedom.</b> Welch–Satterthwaite over what was estimated: each side's excess
     * over Poisson, {@code (φᵢ−1)/xᵢ}, carries one degree per contributing session less one (a
     * borrowed φ carries its owner's), the Poisson part carries none to lose. And never more than the
     * sessions that contributed, less the two rates — the cluster-robust small-sample rule: a φ
     * estimated from few sessions comes out low exactly when the interval most needs it, and with
     * six sessions a side the uncapped estimate still called a difference that was not there in
     * 7–12% of simulated comparisons. Fewer than three contributing sessions, or a cohort of one
     * session, give 0: no interval. Counts get the ratio's Haldane half when either is zero.
     */
    static Spread spread(final Side base, final Side cand) {
        if (base.count() == 0 && cand.count() == 0) {
            return new Spread(1.0, Double.POSITIVE_INFINITY);
        }
        final double correction = base.count() == 0 || cand.count() == 0 ? 0.5 : 0.0;
        final double a1 = 1.0 / (base.count() + correction);
        final double a2 = 1.0 / (cand.count() + correction);
        double phi1 = Math.max(1.0, base.phi());
        double phi2 = Math.max(1.0, cand.phi());
        final boolean own1 = base.showsSpread();
        final boolean own2 = cand.showsSpread();
        if (!own1 && own2) {
            phi1 = Math.max(phi1, phi2);
        } else if (own1 && !own2) {
            phi2 = Math.max(phi2, phi1);
        }
        final double variance = phi1 * a1 + phi2 * a2;
        final double phi = variance / (a1 + a2);

        final long cap = base.contributing() + cand.contributing() - 2;
        if (cap < 1 || base.units() < 2 || cand.units() < 2) {
            return new Spread(phi, 0.0);
        }
        double denominator = 0.0;
        if (own1 && own2) {
            denominator += term((phi1 - 1.0) * a1, base.contributing() - 1, variance);
            denominator += term((phi2 - 1.0) * a2, cand.contributing() - 1, variance);
        } else {
            // one φ serves both sides, so its excess is one estimate, on its owner's sessions
            final Side owner = own1 ? base : cand;
            denominator += term((phi - 1.0) * (a1 + a2), owner.contributing() - 1, variance);
        }
        final double welch = denominator == 0.0 ? Double.POSITIVE_INFINITY : variance * variance / denominator;
        return new Spread(phi, Math.min(welch, cap));
    }

    /** One estimated excess's share of the Welch–Satterthwaite denominator; nothing if negligible. */
    private static double term(final double excess, final long degrees, final double variance) {
        return excess <= 1e-9 * variance ? 0.0 : excess * excess / degrees;
    }

    /** One side's per-session exposure and findings, for the dispersion estimate. */
    private static final class Sessions {
        private final Map<String, Long> calls = new HashMap<>();
        private final List<CohortRepository.SessionFindings> findings = new ArrayList<>();

        Sessions(final String key, final List<CohortRepository.SessionCalls> allCalls,
                 final List<CohortRepository.SessionFindings> allFindings) {
            allCalls.stream().filter(c -> c.key().equals(key))
                    .forEach(c -> calls.merge(c.sessionId(), c.calls(), Long::sum));
            allFindings.stream().filter(f -> f.key().equals(key)).forEach(findings::add);
        }

        long total(final Predicate<CohortRepository.SessionFindings> which) {
            return findings.stream().filter(which).mapToLong(CohortRepository.SessionFindings::count).sum();
        }

        long contributing(final Predicate<CohortRepository.SessionFindings> which) {
            return findings.stream().filter(which).map(CohortRepository.SessionFindings::sessionId).distinct().count();
        }

        /** Every session that contributed exposure or a finding — the units the dispersion runs over. */
        private java.util.Set<String> units() {
            final java.util.Set<String> ids = new java.util.TreeSet<>(calls.keySet());
            findings.forEach(f -> ids.add(f.sessionId()));
            return ids;
        }

        /**
         * Every session of the side is a cluster, including one with findings but no observed call
         * in the selection — a turn that died before its first tool call, or calls that fall outside
         * the window its findings fall inside. Left out, a burst from one such session passed for
         * independent evidence; RateRatio.dispersion counts its residual without dividing by an
         * exposure it does not have.
         */
        double dispersion(final Predicate<CohortRepository.SessionFindings> which) {
            final Map<String, Long> perSession = new HashMap<>();
            findings.stream().filter(which).forEach(f -> perSession.merge(f.sessionId(), f.count(), Long::sum));
            final List<String> ids = List.copyOf(units());
            final long[] x = new long[ids.size()];
            final long[] n = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                x[i] = perSession.getOrDefault(ids.get(i), 0L);
                n[i] = calls.getOrDefault(ids.get(i), 0L);
            }
            return RateRatio.dispersion(x, n);
        }
    }

    private static Optional<CohortRepository.Cohort> find(final CohortRepository.Result result, final String key) {
        return result.cohorts().stream().filter(c -> c.key().equals(key)).findFirst();
    }

    /** The one other cohort, when there is exactly one; otherwise the caller has to say which. */
    private static String onlyOther(final List<String> keys, final String baseline) {
        final List<String> others = keys.stream().filter(k -> !k.equals(baseline)).toList();
        return others.size() == 1 ? others.getFirst() : null;
    }

    private static String basisNote(final InsightFilter filter, final boolean allInferred,
                                    final boolean baselineChosen, final String groupBy) {
        final List<String> notes = new ArrayList<>();
        notes.add("verdicts read the 95% interval of the rate ratio, not the point estimate, widened by how"
                + " unevenly each failure falls across sessions; inconclusive means the data cannot tell the"
                + " two apart yet");
        if (filter.isActive()) {
            notes.add("shared filters are active, both cohorts are the filtered subset");
        }
        if ("harnessVersion".equals(groupBy) && allInferred) {
            notes.add("harness_version is attributed, not declared by the harness");
        }
        if (baselineChosen) {
            notes.add("baseline chosen by highest tool-call count");
        }
        return String.join("; ", notes);
    }
}
