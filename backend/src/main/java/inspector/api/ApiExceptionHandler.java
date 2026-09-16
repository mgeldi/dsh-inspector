package inspector.api;

import inspector.query.UnknownFilterValueException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem details for the read API (DESIGN.md §7): an unknown
 * filter value is a 400 carrying the allowed set, a missing finding is a
 * 404, and any other bad request value is a plain 400.
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
}
