package inspector.api;

import inspector.dto.JudgeDto;
import inspector.insight.JudgeService;
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
 * The judge (DESIGN.md §7a): bind the shared filter, the axis and the two cohorts, and delegate.
 * The statistics and the defaults are {@link JudgeService}'s.
 */
@RestController
@RequestMapping("/api/judge")
public final class JudgeController {

    private final JudgeService judgeService;

    public JudgeController(final JudgeService judgeService) {
        this.judgeService = judgeService;
    }

    @Operation(summary = "Is the candidate cohort better or worse than the baseline, beyond chance?",
            description = "Per failure — all findings, each plane, each code — the rate ratio of candidate"
                    + " over baseline with its 95% interval, and a verdict read from the interval: better or"
                    + " worse only when the whole interval is on one side of 1. Same axes and shared filter"
                    + " as /api/cohorts.")
    @GetMapping
    public JudgeDto judge(
            @ParameterObject @ModelAttribute final InsightFilter filter,
            @Parameter(description = "The axis the two cohorts are values of. One of harnessVersion, model,"
                    + " provider, role, schema, preset.", example = "harnessVersion", required = true)
            @RequestParam final String groupBy,
            @Parameter(description = "Cohort to compare against. Defaults to the one with the most tool calls.")
            @RequestParam(required = false) final String baseline,
            @Parameter(description = "Cohort to judge. May be omitted when the axis has exactly two cohorts.")
            @RequestParam(required = false) final String candidate) {
        return judgeService.judge(filter, groupBy, baseline, candidate);
    }
}
