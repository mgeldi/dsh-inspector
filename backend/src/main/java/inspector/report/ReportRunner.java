package inspector.report;

import inspector.dto.CohortDto;
import inspector.dto.JudgeDto;
import inspector.insight.CohortService;
import inspector.insight.FindingsService;
import inspector.insight.JudgeService;
import inspector.insight.OverviewService;
import inspector.query.InsightFilter;
import inspector.query.UnknownFilterValueException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes {@link AnalysisReport} after the startup index, when {@code inspector.report.path} is
 * set. With {@code --spring.main.web-application-type=none} the process then exits: index, write,
 * done — no port, no browser, which is the shape an agent in a loop can run between two harness
 * changes. With the web server on, the report is written once and the dashboard keeps serving.
 *
 * <p>It reads through the same services as the API, so the file and the screen cannot disagree.
 */
@Component
@Order(100)
public class ReportRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReportRunner.class);
    /** The axes that separate the things a harness change is judged across. */
    static final List<String> AXES = List.of("harnessVersion", "model", "provider", "role");

    private final ReportProperties properties;
    private final OverviewService overview;
    private final CohortService cohorts;
    private final JudgeService judge;
    private final FindingsService findings;
    private final ObjectMapper mapper;

    public ReportRunner(final ReportProperties properties, final OverviewService overview,
                        final CohortService cohorts, final JudgeService judge, final FindingsService findings,
                        final ObjectMapper mapper) {
        this.properties = properties;
        this.overview = overview;
        this.cohorts = cohorts;
        this.judge = judge;
        this.findings = findings;
        this.mapper = mapper;
    }

    @Override
    public void run(final ApplicationArguments args) {
        if (properties.path() == null || properties.path().isBlank()) {
            return;
        }
        final String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(build());
        if ("-".equals(properties.path())) {
            System.out.println(json);
            return;
        }
        try {
            final Path out = Path.of(properties.path());
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, json);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot write the report", e);
        }
        // No path in the line: the report sits wherever the operator asked, which may be under a
        // directory that carries the username.
        LOG.info("wrote the analysis report ({} bytes)", json.length());
    }

    AnalysisReport build() {
        final InsightFilter all = InsightFilter.none();
        final Map<String, CohortDto.Page> tables = new LinkedHashMap<>();
        AXES.forEach(axis -> tables.put(axis, cohorts.cohorts(all, axis, null)));
        JudgeDto verdicts = null;
        String note = null;
        try {
            verdicts = judge.judge(all, properties.groupBy(), properties.baseline(), properties.candidate());
        } catch (final UnknownFilterValueException | IllegalArgumentException e) {
            note = "no judge: " + e.getMessage()
                    + " — set inspector.report.baseline and inspector.report.candidate to two cohorts of "
                    + properties.groupBy();
        }
        return new AnalysisReport(AnalysisReport.FORMAT, System.currentTimeMillis(), overview.overview(all),
                overview.breakdown(all), tables, verdicts, note,
                findings.page(all, null, null, null, null, "time:desc", 0, Math.max(1, Math.min(200, properties.recent()))));
    }
}
