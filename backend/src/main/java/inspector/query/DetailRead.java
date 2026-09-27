package inspector.query;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The second explicit exemption from the shared filter contract of DESIGN.md §7: a store read that
 * answers about one finding — the rows of its own stream around it — rather than about a
 * population. The rail's filters describe which sessions a screen covers; once a single finding is
 * open, its neighbourhood is whatever its stream holds, and a filter that hid part of it would
 * make the sequence lie. {@code FilterContractTest} accepts this in place of a filter parameter,
 * and fails on a blank reason, exactly as for {@link IndexWideRead}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DetailRead {

    /** Why this read is scoped to one finding. Required; a blank reason fails the lint. */
    String reason();
}
