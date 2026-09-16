package inspector.api;

import inspector.dto.OverviewDto;
import inspector.insight.OverviewService;
import inspector.query.InsightFilter;
import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard board (DESIGN.md §7): one GET, the shared {@link InsightFilter} binding, one
 * call. The aggregates, the four differently-rooted WHEREs and the chart merge are
 * {@link OverviewService}'s.
 */
@RestController
@RequestMapping("/api/overview")
public final class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(final OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @Operation(summary = "The dashboard board",
            description = "Tiles, plane mix, top detectors, the two daily series, step throughput, "
                    + "and the bounded filter vocabulary the rail offers. Counts are over the "
                    + "filtered index; tool-call counts exclude outcome-only rows. No evidence text.")
    @GetMapping
    public OverviewDto overview(@ParameterObject @ModelAttribute final InsightFilter filter) {
        return overviewService.overview(filter);
    }
}
