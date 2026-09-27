package inspector.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import inspector.dto.FindingsPageDto;
import inspector.query.FindingsQuery;
import inspector.query.InsightFilter;
import inspector.query.UnknownFilterValueException;
import inspector.query.Vocabulary;
import inspector.store.FindingRepository;
import inspector.store.ToolCallRepository;
import inspector.store.VocabularyService;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * The findings page's two rules that are not SQL and not HTTP: the sort whitelist and the
 * bounds on pagination.
 *
 * <p>{@code sortOf} is the only place a caller-supplied string reaches an ordering, so its output
 * is asserted in full: attribute, direction, and the {@code id} tiebreaker that makes two pages
 * agree on which row is which. {@code FindingsControllerTest} keeps the
 * wire versions of the same cases, because a rejected sort also has to arrive as a 400 with
 * the allowed set and leave the table intact.
 */
class FindingsServiceTest {

    private static final InsightFilter NOTHING_SELECTED =
            InsightFilter.none();

    private final FindingRepository repository = mock(FindingRepository.class);
    private final FindingsService service =
            new FindingsService(repository, mock(ToolCallRepository.class), vocabulary(), ReadSnapshot.none());

    /** A sort, spelled as one line: {@code attribute dir, attribute dir}. */
    private static String order(final String token) {
        final Sort sort = FindingsService.sortOf(token);
        return sort.stream()
                .map(o -> o.getProperty() + " " + o.getDirection().name().toLowerCase())
                .collect(Collectors.joining(", "));
    }

    @Test
    void theDefaultSortIsTimeDescendingWithTheRowIdTiebreaker() {
        assertThat(order("time:desc"))
                .isEqualTo("occurredAt desc, id desc");
        assertThat(order("time"))
                .as("a bare key means descending, which is what the table opens with")
                .isEqualTo("occurredAt desc, id desc");
    }

    @Test
    void everyWhitelistedKeySortsOnItsOwnColumnInBothDirections() {
        assertThat(order("plane:asc")).isEqualTo("plane asc, id asc");
        assertThat(order("detector:asc")).isEqualTo("detector asc, id asc");
        assertThat(order("code:desc")).isEqualTo("code desc, id desc");
        assertThat(order("session:asc")).isEqualTo("sessionId asc, id asc");
        assertThat(order("confidence:desc"))
                .isEqualTo("confidence desc, id desc");
    }

    /**
     * One token, one tolerance: both halves are matched case-insensitively. This used to be
     * asymmetric — {@code time:DESC} worked and {@code TIME:desc} was refused, with a comment
     * arguing that folding the key "would let two spellings of a sort mean the same thing in two
     * different places". That argument cannot survive the same method folding the direction two
     * lines later: the rule the API actually needs is the one in {@link #dataValuesAreCaseSensitive},
     * data values are matched exactly because they are data, and a whitelist key is not data.
     * The 400 still quotes the value as it was sent.
     */
    @Test
    void bothHalvesOfTheSortTokenAreFoldedAndTheOffenderIsQuotedAsSent() {
        assertThat(order("time:DESC")).isEqualTo("occurredAt desc, id desc");
        assertThat(order("plane:Asc")).isEqualTo("plane asc, id asc");
        assertThat(order("TIME:desc")).isEqualTo("occurredAt desc, id desc");
        assertThat(order("Confidence:DESC"))
                .isEqualTo(order("confidence:desc"));

        assertThatThrownBy(() -> order("TIME:drop"))
                .isInstanceOfSatisfying(UnknownFilterValueException.class, ex -> {
                    assertThat(ex.filter()).isEqualTo("sort");
                    assertThat(ex.value()).isEqualTo("drop");
                    assertThat(ex.allowed()).containsExactly("asc", "desc");
                });

        assertThatThrownBy(() -> order("tyme:desc"))
                .isInstanceOfSatisfying(UnknownFilterValueException.class, ex -> {
                    assertThat(ex.filter()).isEqualTo("sort");
                    assertThat(ex.value()).isEqualTo("tyme");
                });
    }

    /**
     * The half that is strict, and why. Vocabulary values are data: {@code V3} and {@code v3} are
     * different answers about the real index, so folding them would silently widen a filter. A
     * whitelist key is a constant, which is why {@link #bothHalvesOfTheSortTokenAreFoldedAndTheOffenderIsQuotedAsSent}
     * can fold and this must not.
     */
    @Test
    void dataValuesAreCaseSensitive() {
        assertThat(order("session:asc")).isEqualTo("sessionId asc, id asc");
        assertThatThrownBy(() -> order("session:ASCEND"))
                .isInstanceOf(UnknownFilterValueException.class);
    }

    /**
     * The injection case, asked of the thing that actually builds the clause. A value that is
     * not a whitelisted key never reaches SQL, and the answer names the keys that do exist.
     */
    @Test
    void aSortValueThatIsNotAKeyIsRejectedWithTheKeysThatAre() {
        assertThatThrownBy(() ->
                order("occurred_at desc; drop table finding"))
                .isInstanceOfSatisfying(UnknownFilterValueException.class, ex -> {
                    assertThat(ex.filter()).isEqualTo("sort");
                    // exactly, in order: this list is what a person reads to fix their request,
                    // and it was assembled from a Map.of keySet, whose order the JDK leaves to a
                    // hash. Written out, it stays in the order the table's columns read.
                    assertThat(ex.allowed()).containsExactly(
                            "time", "plane", "detector", "code", "session", "confidence");
                });
    }

    @Test
    void pageAndSizeHaveBoundsAndSaySo() {
        when(repository.page(any(), any())).thenReturn(Page.empty());

        assertThatThrownBy(() -> service.page(NOTHING_SELECTED, null, null, null, null, "time:desc", -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("page must be >= 0, was -1");
        assertThatThrownBy(() -> service.page(NOTHING_SELECTED, null, null, null, null, "time:desc", 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be > 0, was 0");
        // The upper bound is the one that mattered: before it, ?size=1000000 was a valid request
        // and the answer was a million rows through the query, the mapper and the JSON writer.
        assertThatThrownBy(() -> service.page(NOTHING_SELECTED, null, null, null, null, "time:desc", 0, 201))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be <= 200, was 201");
        assertThat(service.page(NOTHING_SELECTED, null, null, null, null, "time:desc", 0, 200).size())
                .isEqualTo(200);
    }

    /**
     * A plane that is not in the index is a rejection, not an empty page. The difference is
     * the difference between "your filter is wrong" and "this code produced no findings".
     */
    @Test
    void theFindingsAxisIsValidatedAgainstTheVocabularyLikeTheRailIs() {
        assertThatThrownBy(() -> service.page(NOTHING_SELECTED, "Bogus", null, null, null, "time:desc", 0, 20))
                .isInstanceOfSatisfying(UnknownFilterValueException.class, ex -> {
                    assertThat(ex.filter()).isEqualTo("plane");
                    assertThat(ex.allowed()).containsExactly("INFRASTRUCTURE", "GUARD", "MODEL_MISUSE");
                });
        assertThatThrownBy(() -> service.page(NOTHING_SELECTED, null, null, "no-such-session", null,
                "time:desc", 0, 20))
                .isInstanceOfSatisfying(UnknownFilterValueException.class,
                        ex -> assertThat(ex.filter()).isEqualTo("session"));
    }

    @Test
    void thePageCarriesTheAxisFiltersIntoTheQueryAndTheSortIntoThePageable() {
        when(repository.page(any(), any())).thenAnswer(inv ->
                new PageImpl<>(List.of(), inv.getArgument(1, Pageable.class), 7));

        final FindingsPageDto page = service.page(NOTHING_SELECTED, "GUARD", "stamp-guard",
                "s-01", "FS_STALE_VERSION", "confidence:asc", 2, 5);

        assertThat(page.total()).isEqualTo(7);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(5);

        final ArgumentCaptor<FindingsQuery> query = ArgumentCaptor.forClass(FindingsQuery.class);
        final ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).page(query.capture(), pageable.capture());

        assertThat(query.getValue()).isEqualTo(
                new FindingsQuery(NOTHING_SELECTED, "GUARD", "stamp-guard", "FS_STALE_VERSION", "s-01"));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort()).isEqualTo(FindingsService.sortOf("confidence:asc"));
    }

    @Test
    void aDetailLookUpThatFindsNothingIsAbsenceRatherThanAnException() {
        when(repository.findDetail(anyLong())).thenReturn(Optional.empty());
        assertThat(service.detail(999_999L)).isEmpty();
    }

    private static VocabularyService vocabulary() {
        final VocabularyService service = mock(VocabularyService.class);
        when(service.vocabulary()).thenReturn(new Vocabulary(
                List.of("V0", "V3"), List.of("model-a", "unknown"), List.of("default"),
                List.of("0.1.5-rc.2"), List.of("FS_STALE_VERSION"),
                List.of("stamp-guard"), List.of("demo-local"), List.of("orchestrator"), List.of("s-01")));
        return service;
    }
}
