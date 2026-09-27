package inspector.api;

import inspector.dto.FindingContextDto;
import inspector.dto.FindingDetailDto;
import inspector.dto.FindingsPageDto;
import inspector.insight.FindingsService;
import inspector.query.InsightFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The findings table and the one route allowed to return evidence text (DESIGN.md §7, §4.1).
 *  Binding only: the sort whitelist, the vocabulary checks and the page bounds are {@link FindingsService}'s. */
@RestController
@RequestMapping("/api/findings")
public final class FindingsController {

    private final FindingsService findingsService;

    public FindingsController(final FindingsService findingsService) {
        this.findingsService = findingsService;
    }

    @Operation(summary = "One page of findings",
            description = "The aggregate shape only — no evidence text. Every filter value must be "
                    + "one the index actually contains; anything else is a 400 naming the allowed set.")
    @GetMapping
    public FindingsPageDto page(@ParameterObject @ModelAttribute final InsightFilter filter,
            @Parameter(description = "Plane of the finding.", example = "GUARD")
            @RequestParam(required = false) final String plane,
            @Parameter(description = "Detector id that produced the finding.", example = "stamp-guard")
            @RequestParam(required = false) final String detector,
            @Parameter(description = "Session id. Validated against the index even though the rail "
                    + "does not offer it — see VocabularyOptions.")
            @RequestParam(required = false) final String session,
            @Parameter(description = "Error code of the finding.", example = "FS_STALE_VERSION")
            @RequestParam(required = false) final String code,
            @Parameter(description = "Sort as key[:dir]. Keys: time, plane, detector, code, session, "
                    + "confidence. Direction asc or desc. Both halves are case-insensitive, and the "
                    + "row id breaks ties so paging is stable.", example = "time:desc")
            @RequestParam(defaultValue = "time:desc") final String sort,
            @Parameter(description = "Zero-based page index.", example = "0")
            @RequestParam(defaultValue = "0") final int page,
            @Parameter(description = "Rows per page.", example = "20")
            @RequestParam(defaultValue = "20") final int size) {
        return findingsService.page(filter, plane, detector, session, code, sort, page, size);
    }

    @Operation(summary = "The tool-call sequence around one finding",
            description = "The calls of the finding's own stream before and after it, and the findings"
                    + " among them: tool names, outcome codes, project-relative paths and timings — no text"
                    + " of any kind. A finding with no tool-call seq of its own (a fatal turn, a retry"
                    + " storm) is placed at the last call that had started by its event time.")
    @GetMapping("/{id}/context")
    public FindingContextDto context(
            @Parameter(description = "Finding id, as served by the findings page.", example = "1")
            @PathVariable final long id,
            @Parameter(description = "Calls to show on each side, 1 to 50.", example = "12")
            @RequestParam(defaultValue = "12") final int window) {
        return findingsService.context(id, window).orElseThrow(() -> new FindingNotFoundException(id));
    }

    @Operation(summary = "One finding, with its evidence",
            description = "The only route that returns text of any kind: the truncated, "
                    + "credential-masked command excerpts behind the finding (DESIGN.md §4.1).")
    @GetMapping("/{id}")
    public FindingDetailDto detail(
            @Parameter(description = "Finding id, as served by the findings page.", example = "1")
            @PathVariable final long id) {
        return findingsService.detail(id).orElseThrow(() -> new FindingNotFoundException(id));
    }
}
