package inspector.query;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The explicit exemption from the shared filter contract of DESIGN.md §7: a store
 * read that deliberately answers over the whole index. The reason lives with the
 * method, where it can be read and argued, and {@code FilterContractTest} fails
 * the build on a read that neither takes a {@link FindingFilters} nor carries
 * this annotation — and on an annotation whose reason is blank, because a blank
 * excuse is not an excuse.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IndexWideRead {

    /** Why this read is index-wide. Required; a blank reason fails the lint. */
    String reason();
}
