package inspector.api;

import inspector.index.IndexAlreadyRunningException;
import inspector.query.UnknownFilterValueException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details for the read API (DESIGN.md §7): an unknown filter value is a 400
 * carrying the allowed set, a missing finding is a 404, any other bad request value is a plain
 * 400, and a second index run while one is in flight is a 409 — a conflict with the server's
 * current state, not a malformed request.
 */
@RestControllerAdvice
public final class ApiExceptionHandler {

    @ExceptionHandler(UnknownFilterValueException.class)
    public ProblemDetail handleUnknownFilterValue(final UnknownFilterValueException ex) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Unknown filter value");
        problem.setType(URI.create("urn:dsh-inspector:unknown-filter-value"));
        problem.setProperty("filter", ex.filter());
        problem.setProperty("value", ex.value());
        problem.setProperty("allowed", ex.allowed());
        return problem;
    }

    @ExceptionHandler(FindingNotFoundException.class)
    public ProblemDetail handleFindingNotFound(final FindingNotFoundException ex) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Finding not found");
        problem.setType(URI.create("urn:dsh-inspector:finding-not-found"));
        problem.setProperty("id", ex.id());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(final IllegalArgumentException ex) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad request");
        problem.setType(URI.create("urn:dsh-inspector:bad-request"));
        return problem;
    }

    /**
     * 409, not 400: nothing is wrong with the request, the server is already doing it. The
     * detail is written to be read out loud by the browser — the frontend shows this one as a
     * plain statement rather than as an error, so the sentence has to stand on its own.
     */
    @ExceptionHandler(IndexAlreadyRunningException.class)
    public ProblemDetail handleIndexAlreadyRunning(final IndexAlreadyRunningException ex) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Index already running");
        problem.setType(URI.create("urn:dsh-inspector:index-already-running"));
        return problem;
    }
}
