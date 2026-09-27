import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { PLANE_COLOURS } from '../charts/theme';
import type { FindingContextDto, FindingDetailDto } from '../api/types';
import { FindingSequence } from './finding-sequence';
import {
  categoryChip, chainSteps, confidenceLabel, confidenceTip, planeLabel, timeLong,
} from './finding-words';

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
  imports: [FindingSequence],
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
          @if (f.detail) {
            <!-- The harness sub-code behind the code: "SERVER" says who failed, this says how.
                 Styled as a code (the stylesheet sits at its 4 kB budget, so no rule of its own). -->
            <span class="chip code-chip detail-chip" [attr.title]="'detail: ' + f.detail">{{ f.detail }}</span>
          }
          @if (f.category) {
            <span class="chip cat-chip">{{ categoryChip(f.category).label }}</span>
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

      @let steps = chainSteps(f);
      @if (steps.length > 0) {
        <!-- The chain is one reading: a timeline the eye follows top to bottom, the dots in
             the finding's plane colour, the hairline connecting them. -->
        <section class="chain" aria-label="Causal chain" [style.--plane]="PLANE_COLOURS[f.plane]">
          <h3 class="section-title">Causal chain</h3>
          <ol class="chain-steps">
            @for (s of steps; track s.label) {
              <li class="chain-step" [class.final]="s.final">
                <span class="chain-dot"></span>
                <span class="chain-seq num">{{ s.seq }}</span>
                <span class="chain-label">{{ s.label }}</span>
              </li>
            }
          </ol>
        </section>
      }

      <!-- What the stream did around it, in order: the chain names three calls, this shows the
           ones between and beside them. Its own component, with its own style budget. -->
      <app-finding-sequence [finding]="f" [context]="context()" [failed]="contextFailed()" [reason]="contextReason()" (openFinding)="openFinding.emit($event)" />

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
            No evidence attached — evidence is recorded for stamp-guard and shell-edit findings only.
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
  /** The calls around the finding; null while it is on its way, or when it could not be had. */
  readonly context = input<FindingContextDto | null>(null);
  readonly contextFailed = input(false);
  /** Why the sequence failed, shown in its place. */
  readonly contextReason = input<string | null>(null);
  readonly close = output<void>();
  /** A neighbour in the sequence was chosen: the host opens it like a row. */
  readonly openFinding = output<number>();
  readonly PLANE_COLOURS = PLANE_COLOURS;
  readonly planeLabel = planeLabel;
  readonly categoryChip = categoryChip;
  readonly chainSteps = chainSteps;
  readonly confidenceLabel = confidenceLabel;
  readonly confidenceTip = confidenceTip;
  readonly fmtTime = timeLong;
}
