package inspector.api;

import inspector.dto.BreakdownDto;
import inspector.insight.OverviewService;
import inspector.query.InsightFilter;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Findings by kind over the shared filter (DESIGN.md §7a). */
@RestController
@RequestMapping("/api/breakdown")
public final class BreakdownController {

    private final OverviewService overviewService;

    public BreakdownController(final OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @Operation(summary = "Findings by kind: detector, category, code and detail",
            description = "Busiest first, each with its rate per 1,000 observed tool calls of the same"
                    + " selection. The finest grain the detectors produce — the table a lesson is read from.")
    @GetMapping
    public List<BreakdownDto> breakdown(@ParameterObject @ModelAttribute final InsightFilter filter) {
        return overviewService.breakdown(filter);
    }
}
