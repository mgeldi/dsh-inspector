package inspector.api;

import inspector.index.IndexService;
import inspector.index.IndexSummary;

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

    @PostMapping("/run")
    public IndexSummary run() {
        return indexService.run();
    }
}
