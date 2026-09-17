import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { PLANE_COLOURS } from '../charts/theme';
import type { Category, FindingDetailDto, Plane } from '../api/types';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function pad2(n: number): string { return String(n).padStart(2, '0'); }

/**
 * Local-time date formatting without DatePipe: the pipe's locale table gets hoisted into
 * the initial bundle by linker dedupe (measured: one extra ɵpipe and ~11 kB in main),
 * while these cells are not an i18n surface. The helpers cost nothing in the bundle.
 */
export function timeShort(ms: number): string {
  const d = new Date(ms);
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

export function timeLong(ms: number): string {
  const d = new Date(ms);
  return `${MONTHS[d.getMonth()]} ${d.getDate()}, ${d.getFullYear()} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

const PLANE_LABELS: Record<Plane, string> = {
  GUARD: 'Guard',
  MODEL_MISUSE: 'Model misuse',
  INFRASTRUCTURE: 'Infrastructure',
};

export function planeLabel(p: Plane): string {
  return PLANE_LABELS[p];
}

/**
 * The §5.3 confidence tier as a word: 0.9 → high, 0.6 → medium. A null confidence is
 * never a dash and never 0 — a zero would assert "the model is certainly not the cause",
 * the opposite of what null means.
 *
 * <p>But null means two different things, and rendering both as 'unattributed' was a lie
 * the backend had already forbidden in prose: {@code ErrorPlaneDetector} says the UI
 * "distinguishes these by detector, not by rendering every null as 'unattributed'", and
 * the UI did exactly that. A finding from a detector that performs no attribution at all
 * was labelled as one whose attribution had been attempted and had failed.
 *
 * <p>The signal is `category`, not the detector id: stamp-guard is the only detector that
 * attributes, and it always records which of the three outcomes it reached, while the
 * other three pass null. So a null category is "no attribution model applies here" —
 * a fact about the row rather than a name the frontend has to know.
 */
export function confidenceLabel(c: number | null, category: Category | null): string {
  if (c === null) { return category === null ? 'n/a' : 'unattributed'; }
  if (c >= 0.9) { return 'high'; }
  if (c >= 0.6) { return 'medium'; }
  return 'low';
}

/** The tooltip on a confidence label: what each tier measured, per §5.3. */
export function confidenceTip(c: number | null, category: Category | null): string {
  switch (confidenceLabel(c, category)) {
    case 'high': return 'high: absolute-path match plus a mutating verb';
    case 'medium': return 'medium: basename match plus a mutating verb';
    case 'low': return 'low: text-pattern fallback';
    case 'unattributed': return 'unattributed: a cause was looked for in the window and none was found — not that none existed';
    default: return 'not applicable: this detector reports the error, it does not attribute a cause — only stamp-guard does';
  }
}

/**
 * The side panel for one finding — the only place evidence text appears (§4.1).
 * The loudest thing in the panel is the causal chain: stamped → changed → refused,
 * the §5.3 attribution made visible, so a reader can judge the finding from the
 * panel alone. `EXTERNAL` findings carry no evidence section at all: there is none,
 * and an empty box invites the question one sentence answers.
 */
@Component({
  selector: 'app-finding-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let d = detail();
    @let f = d.finding;
    <aside class="detail" aria-label="Finding detail">
      <header class="detail-head">
        <div class="title-row">
          <span class="dot" [style.background]="PLANE_COLOURS[f.plane]"></span>
          <h2 class="title">{{ f.detector }}</h2>
          <span class="when muted num">{{ fmtTime(f.occurredAt) }}</span>
          <button type="button" class="close" aria-label="Close detail" (click)="close.emit()">×</button>
        </div>
        <p class="summary">{{ f.summary }}</p>
        <p class="meta">
          <span class="chip">{{ planeLabel(f.plane) }}</span>
          @if (f.code) {
            <span class="chip code-chip">{{ f.code }}</span>
          }
          <span class="chip conf" [class.unattributed]="f.confidence === null" [attr.title]="confidenceTip(f.confidence, f.category)">{{ confidenceLabel(f.confidence, f.category) }}</span>
          @if (f.pathHint) {
            <span class="chip path-chip" [attr.title]="f.pathHint">{{ f.pathHint }}</span>
          }
        </p>
        @if (d.tool) {
          <p class="tool muted">tool: <span class="tool-name">{{ d.tool }}</span></p>
        }
      </header>

      @if (f.staleSeq !== null || f.causeSeq !== null || f.seq !== null) {
        <!-- The chain is one reading: a timeline the eye follows top to bottom, the dots in
             the finding's plane colour, the hairline connecting them. -->
        <section class="chain" aria-label="Causal chain" [style.--plane]="PLANE_COLOURS[f.plane]">
          <h3 class="section-title">Causal chain</h3>
          <ol class="chain-steps">
            @if (f.staleSeq !== null) {
              <li class="chain-step">
                <span class="chain-dot"></span>
                <span class="chain-seq num">{{ f.staleSeq }}</span>
                <span class="chain-label">stamped at {{ f.staleSeq }}</span>
              </li>
            }
            @if (f.causeSeq !== null) {
              <li class="chain-step">
                <span class="chain-dot"></span>
                <span class="chain-seq num">{{ f.causeSeq }}</span>
                <span class="chain-label">changed at {{ f.causeSeq }}</span>
              </li>
            }
            @if (f.seq !== null) {
              <li class="chain-step final">
                <span class="chain-dot"></span>
                <span class="chain-seq num">{{ f.seq }}</span>
                <span class="chain-label">refused at {{ f.seq }}</span>
              </li>
            }
          </ol>
        </section>
      }

      @if (f.category !== 'EXTERNAL') {
        @if (d.evidence.length > 0) {
          <section class="evidence" aria-label="Evidence">
            <h3 class="section-title">Evidence</h3>
            @for (e of d.evidence; track e.seq) {
              <div class="ev">
                <div class="ev-head">
                  <span class="ev-seq num">seq {{ e.seq }}</span>
                  <span
                    class="ev-verb"
                    [class.verb-mutating]="e.verbClass === 'MUTATING'"
                    [class.verb-restoring]="e.verbClass === 'VCS_RESTORE'"
                  >{{ e.verbClass }}</span>
                  @if (e.pathHint) {
                    <span class="ev-path muted" [attr.title]="e.pathHint">{{ e.pathHint }}</span>
                  }
                </div>
                <!-- The redacted command, on an inset surface: it scrolls sideways rather than
                     wrapping mid-command. This is the only place command text appears. -->
                <code class="ev-excerpt">{{ e.excerptRedacted }}</code>
              </div>
            }
          </section>
        } @else {
          <p class="no-evidence muted">
            No evidence attached — evidence is recorded for stamp-guard findings only.
          </p>
        }
      } @else {
        <p class="no-evidence muted">
          No evidence is stored for this finding — the cause could not be attributed from the window,
          so there is none by design.
        </p>
      }
    </aside>
  `,
  styleUrl: './finding-detail.scss',
})
export class FindingDetail {
  readonly detail = input.required<FindingDetailDto>();
  readonly close = output<void>();
  readonly PLANE_COLOURS = PLANE_COLOURS;
  readonly planeLabel = planeLabel;
  readonly confidenceLabel = confidenceLabel;
  readonly confidenceTip = confidenceTip;
  readonly fmtTime = timeLong;
}
