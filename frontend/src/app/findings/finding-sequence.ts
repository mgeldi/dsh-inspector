import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { PLANE_COLOURS } from '../charts/theme';
import type { Category, ContextCall, FindingContextDto, FindingDto } from '../api/types';
import { categoryChip, chainWords } from './finding-words';

/**
 * The tool calls around one finding, one line each: seq, tool, outcome code, path hint. The
 * causal chain above names at most three calls; this is the stream between and beside them —
 * a failed edit followed by the same edit, a read that came three calls too early, a shell
 * rewrite in the middle of a run of file-tool calls. Structure only: there is no text to show,
 * and the endpoint sends none.
 *
 * <p>The calls the finding points at carry the chain's own words ("read", "edit missed"), so
 * the two sections read as one account. Other findings in the window are buttons: the next
 * miss in a retry run is usually the next thing a reader wants to open.
 */
@Component({
  selector: 'app-finding-sequence',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="seq" aria-labelledby="seq-title" [style.--plane]="colour()">
      <h3 class="seq-title" id="seq-title">Sequence</h3>
      @let ctx = context();
      @if (ctx === null && failed()) {
        <p class="seq-note muted">The calls around it could not be loaded{{ reason() ? ': ' + reason() : '.' }}</p>
      } @else if (ctx === null) {
        <p class="seq-note muted">Loading the calls around it…</p>
      } @else if (ctx.calls.length === 0) {
        <p class="seq-note muted">Its stream holds no tool calls around it.</p>
      } @else {
        <ol class="seq-calls">
          @for (c of ctx.calls; track c.seq) {
            @let word = markWord(c);
            <li class="seq-call" [class.marked]="word !== null" [class.final]="c.mark === 'finding'">
              <span class="seq-line">
                <span class="seq-n num">{{ c.seq }}</span>
                <span class="seq-tool" [attr.title]="c.name">{{ c.name ?? '—' }}</span>
                @if (c.errorCode) {
                  <span class="seq-code" [attr.title]="c.errorCode">{{ c.errorCode }}</span>
                }
                <span class="seq-path" [attr.title]="c.pathHint">{{ c.pathHint ?? '' }}</span>
              </span>
              @if (word) {
                <span class="seq-mark">{{ word }}</span>
              }
            </li>
          }
        </ol>
      }
      @if (neighbours().length > 0) {
        <h4 class="seq-sub">Other findings in this window</h4>
        <ul class="seq-findings">
          @for (n of neighbours(); track n.id) {
            <li>
              <button type="button" class="seq-open" (click)="openFinding.emit(n.id)"
                      [attr.aria-label]="'Open finding ' + n.id + ': ' + n.detector + (n.seq === null ? '' : ' at seq ' + n.seq)">
                <span class="seq-n num">{{ n.seq ?? '—' }}</span>
                <span class="seq-tool" [attr.title]="n.detector">{{ n.detector }}</span>
                @if (n.code) {
                  <span class="seq-code" [attr.title]="n.code">{{ n.code }}</span>
                }
                @if (n.category) {
                  <span class="seq-path" [attr.title]="categoryWord(n.category)">{{ categoryWord(n.category) }}</span>
                }
              </button>
            </li>
          }
        </ul>
      }
    </section>
  `,
  styleUrl: './finding-sequence.scss',
})
export class FindingSequence {
  readonly finding = input.required<FindingDto>();
  /** Null while the calls are on their way. */
  readonly context = input<FindingContextDto | null>(null);
  /** The request for the calls came back as an error: say so instead of waiting for it. */
  readonly failed = input(false);
  /** Why, when it failed: stated here, since the error bar is cleared by any later success. */
  readonly reason = input<string | null>(null);
  readonly openFinding = output<number>();

  readonly colour = computed(() => PLANE_COLOURS[this.finding().plane]);
  private readonly words = computed(() => chainWords(this.finding()));

  /** The other findings in the window; the open one is already the panel. */
  readonly neighbours = computed(() =>
    (this.context()?.findings ?? []).filter(n => n.id !== this.finding().id));

  /**
   * The chain's word for a marked call. A finding with no seq of its own (a fatal turn) marks
   * nothing; its window is centred on the last call that had started, and that call says so.
   */
  markWord(c: ContextCall): string | null {
    const w = this.words();
    if (c.mark === 'finding') { return w.finding; }
    if (c.mark === 'stale') { return w.stale; }
    if (c.mark === 'cause') { return w.cause; }
    const ctx = this.context();
    const unmarked = ctx !== null && !ctx.calls.some(call => call.mark === 'finding');
    return unmarked && c.seq === ctx.anchorSeq ? 'last call before it' : null;
  }

  categoryWord(c: string): string {
    return categoryChip(c as Category)?.label ?? c;
  }
}
