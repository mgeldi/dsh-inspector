package inspector.api;

import inspector.index.IndexService;
import inspector.index.IndexSummary;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one mutating endpoint (DESIGN.md §7): re-run the indexer over the
 * configured corpus and return the run summary synchronously.
 */
@RestController
@RequestMapping("/api/index")
public final class IndexController {

    private final IndexService indexService;

    public IndexController(final IndexService indexService) {
        this.indexService = indexService;
    }

    @Operation(summary = "Re-index the configured corpus",
            description = "Synchronous, and single-flight: a run makes the database equal to the "
                    + "corpus — streams no longer on disk are pruned and counted — and a second "
                    + "request while one is running is refused with 409 rather than interleaved.")
    @PostMapping("/run")
    public IndexSummary run() {
        return indexService.run();
    }
}
