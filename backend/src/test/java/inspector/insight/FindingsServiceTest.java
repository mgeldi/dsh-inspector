package inspector.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import inspector.dto.FindingsPageDto;
import inspector.query.FindingFilters;
import inspector.query.InsightFilter;
import inspector.query.UnknownFilterValueException;
import inspector.query.Vocabulary;
import inspector.store.FindingRepository;
import inspector.store.VocabularyService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The findings page's two rules that are not SQL and not HTTP: the sort whitelist and the
 * bounds on pagination.
 *
 * <p>{@code orderClause} is the only place a caller-supplied string reaches an {@code order
 * by}, so its output is asserted as text: column, direction, and the {@code f.id} tiebreaker
 * that makes two pages agree on which row is which. {@code FindingsControllerTest} keeps the
 * wire versions of the same cases, because a rejected sort also has to arrive as a 400 with
 * the allowed set and leave the table intact.
 */
class FindingsServiceTest {

    private static final InsightFilter NOTHING_SELECTED =
            new InsightFilter(null, null, null, null, null, null);

    private final FindingRepository repository = mock(FindingRepository.class);
    private final FindingsService service = new FindingsService(repository, vocabulary());

    @Test
    void theDefaultSortIsTimeDescendingWithTheRowIdTiebreaker() {
        assertThat(FindingsService.orderClause("time:desc"))
                .isEqualTo("f.occurred_at desc, f.id desc");
        assertThat(FindingsService.orderClause("time"))
                .as("a bare key means descending, which is what the table opens with")
                .isEqualTo("f.occurred_at desc, f.id desc");
    }

    @Test
    void everyWhitelistedKeySortsOnItsOwnColumnInBothDirections() {
        assertThat(FindingsService.orderClause("plane:asc")).isEqualTo("f.plane asc, f.id asc");
        assertThat(FindingsService.orderClause("detector:asc")).isEqualTo("f.detector asc, f.id asc");
        assertThat(FindingsService.orderClause("code:desc")).isEqualTo("f.code desc, f.id desc");
        assertThat(FindingsService.orderClause("session:asc")).isEqualTo("f.session_id asc, f.id asc");
        assertThat(FindingsService.orderClause("confidence:desc"))
                .isEqualTo("f.confidence desc, f.id desc");
    }

    /**
     * The direction is folded, the key is not: {@code time:DESC} sorts descending, while
     * {@code TIME:desc} is refused and answered with the keys that exist. Asymmetric, and
     * deliberate enough to pin — a caller who types the key in caps is holding the wrong
     * column name, and the 400 tells them so, whereas silently folding it would let two
     * spellings of a sort mean the same thing in two different places.
     */
    @Test
    void theDirectionIsCaseInsensitiveAndTheKeyIsNot() {
        assertThat(FindingsService.orderClause("time:DESC")).isEqualTo("f.occurred_at desc, f.id desc");
        assertThat(FindingsService.orderClause("plane:Asc")).isEqualTo("f.plane asc, f.id asc");

        assertThatThrownBy(() -> FindingsService.orderClause("TIME:desc"))
                .isInstanceOfSatisfying(UnknownFilterValueException.class, ex -> {
                    assertThat(ex.filter()).isEqualTo("sort");
                    assertThat(ex.value()).isEqualTo("TIME");
                    assertThat(ex.allowed()).contains("time");
                });

        assertThatThrownBy(() -> FindingsService.orderClause("time:drop"))
                .isInstanceOfSatisfying(UnknownFilterValueException.class, ex -> {
                    assertThat(ex.filter()).isEqualTo("sort");
                    assertThat(ex.value()).isEqualTo("drop");
                    assertThat(ex.allowed()).containsExactly("asc", "desc");
                });
    }

    /**
     * The injection case, asked of the thing that actually builds the clause. A value that is
     * not a whitelisted key never reaches SQL, and the answer names the keys that do exist.
     */
    @Test
    void aSortValueThatIsNotAKeyIsRejectedWithTheKeysThatAre() {
        assertThatThrownBy(() ->
                FindingsService.orderClause("occurred_at desc; drop table finding"))
                .isInstanceOfSatisfying(UnknownFilterValueException.class, ex -> {
                    assertThat(ex.filter()).isEqualTo("sort");
                    assertThat(ex.allowed()).containsExactlyInAnyOrder(
                            "time", "plane", "detector", "code", "session", "confidence");
                });
    }

    @Test
    void pageAndSizeHaveBoundsAndSaySo() {
        when(repository.count(any())).thenReturn(0L);
        when(repository.page(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.page(NOTHING_SELECTED, null, null, null, null, "time:desc", -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("page must be >= 0, was -1");
        assertThatThrownBy(() -> service.page(NOTHING_SELECTED, null, null, null, null, "time:desc", 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be > 0, was 0");
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
    void thePageCarriesTheAxisFiltersIntoTheOneWhereClauseAndTheSortIntoTheOther() {
        when(repository.count(any())).thenReturn(7L);
        when(repository.page(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of());

        final FindingsPageDto page = service.page(NOTHING_SELECTED, "GUARD", "stamp-guard",
                "s-01", "FS_STALE_VERSION", "confidence:asc", 2, 5);

        assertThat(page.total()).isEqualTo(7);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(5);

        final ArgumentCaptor<FindingFilters.Sql> where =
                ArgumentCaptor.forClass(FindingFilters.Sql.class);
        final ArgumentCaptor<String> order = ArgumentCaptor.forClass(String.class);
        verify(repository).page(where.capture(), order.capture(), eq(2), eq(5));

        assertThat(order.getValue()).isEqualTo("f.confidence asc, f.id asc");
        assertThat(where.getValue().where())
                .contains("f.plane = ?")
                .contains("f.detector = ?")
                .contains("f.code = ?")
                .contains("f.session_id = ?");
        assertThat(where.getValue().params())
                .as("the values are binds, never fragments of the clause text")
                .containsExactly("GUARD", "stamp-guard", "FS_STALE_VERSION", "s-01");
    }

    @Test
    void aDetailLookUpThatFindsNothingIsAbsenceRatherThanAnException() {
        when(repository.detail(anyLong())).thenReturn(Optional.empty());
        assertThat(service.detail(999_999L)).isEmpty();
    }

    private static VocabularyService vocabulary() {
        final VocabularyService service = mock(VocabularyService.class);
        when(service.vocabulary()).thenReturn(new Vocabulary(
                List.of("V0", "V3"), List.of("model-a", "unknown"), List.of("default"),
                List.of("0.1.5-rc.2"), List.of("FS_STALE_VERSION"),
                List.of("stamp-guard"), List.of("s-01")));
        return service;
    }
}
