package inspector.api;

import inspector.dto.CohortDto;
import inspector.insight.CohortService;
import inspector.query.InsightFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cohorts route (DESIGN.md §7): bind the shared filter plus the axis and delegate. The
 * axis whitelist, the rate maths, the baseline choice and the basis note are
 * {@link CohortService}'s, and that is where their reasoning is written down.
 */
@RestController
@RequestMapping("/api/cohorts")
public final class CohortsController {

    private final CohortService cohortService;

    public CohortsController(final CohortService cohortService) {
        this.cohortService = cohortService;
    }

    @Operation(summary = "Findings per 1,000 observed tool calls, grouped on one axis",
            description = "Rates and deltas against a baseline cohort. Denominators count observed "
                    + "calls only; the response carries a basis note whenever the comparison is a "
                    + "description rather than a comparison — a one-row axis, an all-inferred "
                    + "version, or an active filter.")
    @GetMapping
    public CohortDto.Page cohorts(
            @ParameterObject @ModelAttribute final InsightFilter filter,
            @Parameter(description = "The grouping axis. One of harnessVersion, model, schema, preset.",
                    example = "harnessVersion", required = true)
            @RequestParam final String groupBy,
            @Parameter(description = "Cohort key to compare against. Defaults to the cohort with the "
                    + "most tool calls, and the basis note says so when it was chosen for you.")
            @RequestParam(required = false) final String baseline) {
        return cohortService.cohorts(filter, groupBy, baseline);
    }
}
