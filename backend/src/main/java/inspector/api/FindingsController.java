package inspector.api;

import inspector.dto.FindingDetailDto;
import inspector.dto.FindingsPageDto;
import inspector.insight.FindingsService;
import inspector.query.InsightFilter;
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

    @GetMapping
    public FindingsPageDto page(@ModelAttribute final InsightFilter filter,
            @RequestParam(required = false) final String plane,
            @RequestParam(required = false) final String detector,
            @RequestParam(required = false) final String session, @RequestParam(required = false) final String code,
            @RequestParam(defaultValue = "time:desc") final String sort,
            @RequestParam(defaultValue = "0") final int page, @RequestParam(defaultValue = "20") final int size) {
        return findingsService.page(filter, plane, detector, session, code, sort, page, size);
    }

    @GetMapping("/{id}")
    public FindingDetailDto detail(@PathVariable final long id) {
        return findingsService.detail(id).orElseThrow(() -> new FindingNotFoundException(id));
    }
}
