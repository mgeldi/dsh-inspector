package inspector.dto;

import java.util.List;

/**
 * One page of the findings table: the stable page window plus the total over
 * all pages, so the UI can page without a second roundtrip.
 *
 * @param total rows matching the filters, all pages
 * @param page  zero-based page index
 * @param size  rows per page
 * @param items the rows on this page, sorted per the whitelist
 */
public record FindingsPageDto(long total, int page, int size, List<FindingDto> items) {
}
