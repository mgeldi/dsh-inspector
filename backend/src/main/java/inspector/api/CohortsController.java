package inspector.api;

import inspector.dto.CohortDto;
import inspector.insight.CohortService;
import inspector.query.InsightFilter;
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

    @GetMapping
    public CohortDto.Page cohorts(
            @ParameterObject @ModelAttribute final InsightFilter filter,
            @RequestParam final String groupBy,
            @RequestParam(required = false) final String baseline) {
        return cohortService.cohorts(filter, groupBy, baseline);
    }
}
