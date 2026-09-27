package inspector.report;

import inspector.dto.BreakdownDto;
import inspector.dto.CohortDto;
import inspector.dto.FindingsPageDto;
import inspector.dto.JudgeDto;
import inspector.dto.OverviewDto;
import java.util.Map;

/**
 * Everything an agent improving the harness needs from one index run, in one file: the board,
 * findings by kind, the cohort tables on every axis that separates a model or a harness version,
 * the judge's verdicts, and the most recent findings. Aggregates, generated summaries and
 * project-relative paths only — the same boundary as the API; no evidence excerpt is included.
 * It is still derived from a real corpus, so it is a file to read, not one to publish.
 *
 * @param format      a version for the reader: {@value #FORMAT}
 * @param judgeNote   why {@code judge} is null, when it is
 */
public record AnalysisReport(String format, long generatedAt, OverviewDto overview,
                             java.util.List<BreakdownDto> breakdown, Map<String, CohortDto.Page> cohorts,
                             JudgeDto judge, String judgeNote, FindingsPageDto recentFindings) {

    public static final String FORMAT = "dsh-inspector-report/1";
}
