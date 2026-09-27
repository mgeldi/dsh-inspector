package inspector.report;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The headless report (DESIGN.md §12). Off unless {@code path} is set.
 *
 * @param path      where to write the JSON snapshot; {@code -} for standard output
 * @param groupBy   the axis the judge compares on
 * @param baseline  the judge's baseline cohort; defaults as {@code /api/judge} does
 * @param candidate the judge's candidate cohort; may be omitted when the axis has two cohorts
 * @param recent    how many of the most recent findings to include
 */
@ConfigurationProperties(prefix = "inspector.report")
public record ReportProperties(
        String path,
        @DefaultValue("harnessVersion") String groupBy,
        String baseline,
        String candidate,
        @DefaultValue("50") int recent) {
}
