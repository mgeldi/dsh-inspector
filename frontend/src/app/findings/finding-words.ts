import type { Category, FindingDto, Plane } from '../api/types';

// The findings screen's vocabulary: how a plane, a category, a confidence and a seq are said.
// Pure functions in a module of their own, so the table, the detail panel and the sequence can
// all read them without importing one another's components.

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
 * The category as the chip shows it, and the chip's colour class. Only a direct mutation
 * carries the alarm hue; a restore is legitimate work, and the edit-miss categories are
 * descriptions of what came before a failed edit, so they stay neutral and let the words
 * carry the difference.
 */
const CATEGORY_CHIPS: Record<Category, { label: string; cls: string }> = {
  DIRECT_MUTATION: { label: 'direct mutation', cls: 'cat-mutation' },
  VCS_RESTORE: { label: 'vcs restore · legitimate', cls: 'cat-restore' },
  EXTERNAL: { label: 'external', cls: 'cat-external' },
  REPEATED_MISS: { label: 'repeated miss', cls: 'cat-miss' },
  // The chip column is 148px of content; "own change" clipped it. The chain below says
  // "edit or write", which is what the category covers.
  MISS_AFTER_EDIT: { label: 'miss after own edit', cls: 'cat-miss' },
  MISS_AFTER_READ: { label: 'miss after read', cls: 'cat-miss' },
  // A read of a range, and a quote from outside it: the chain names the read as "partial read".
  MISS_AFTER_PARTIAL_READ: { label: 'miss, partial read', cls: 'cat-miss' },
  MISS_UNREAD: { label: 'miss, file unread', cls: 'cat-miss' },
};

export function categoryChip(c: Category): { label: string; cls: string } {
  return CATEGORY_CHIPS[c];
}

const MISS_CATEGORIES: ReadonlySet<Category | null> =
  new Set<Category | null>(['REPEATED_MISS', 'MISS_AFTER_EDIT', 'MISS_AFTER_READ', 'MISS_AFTER_PARTIAL_READ',
    'MISS_UNREAD']);

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
 * <p>The signal is `category`, not the detector id: the detectors that attribute or
 * categorise (stamp-guard, shell-edit, edit-miss) always record a category, and only
 * stamp-guard's EXTERNAL outcome pairs one with a null confidence; error-plane, fatal-turn
 * and retry-storm pass null for both. So a null category is "no attribution model applies
 * here" — a fact about the row rather than a name the frontend has to know.
 */
export function confidenceLabel(c: number | null, category: Category | null): string {
  if (c === null) { return category === null ? 'n/a' : 'unattributed'; }
  if (c >= 0.9) { return 'high'; }
  if (c >= 0.6) { return 'medium'; }
  return 'low';
}

/**
 * The tooltip on a confidence label: what each tier measured. For a path match (§5.3,
 * stamp-guard and shell-edit) that is the strength of the match; an edit-miss category is
 * read straight off the order of file-tool calls, so its "high" claims no match at all.
 */
export function confidenceTip(c: number | null, category: Category | null): string {
  const label = confidenceLabel(c, category);
  if (label === 'high' && MISS_CATEGORIES.has(category)) {
    return 'high: the category is read off the order of file-tool calls on the path, not inferred';
  }
  switch (label) {
    case 'high': return 'high: absolute-path match plus a mutating verb';
    case 'medium': return 'medium: basename match plus a mutating verb';
    case 'low': return 'low: text-pattern fallback';
    case 'unattributed': return 'unattributed: a cause was looked for in the window and none was found — not that none existed';
    default: return 'not applicable: this detector reports the error, it does not attribute a cause or assign a category';
  }
}

export interface ChainStep { seq: number; label: string; final: boolean; }

/** What each of a finding's three seq fields means, in words; null where the detector sets none. */
export interface ChainWords { stale: string | null; cause: string | null; finding: string; }

/**
 * The words for one finding's seq fields, per the detector that produced it. The three fields
 * are shared by every detector but do not mean the same thing in each: only stamp-guard's are
 * stamped → changed → refused. Painting that sentence over an edit miss would call a read a
 * stamp; over a shell rewrite it would name a refusal that never happened.
 *
 * <p>edit-miss: `causeSeq` is the previous file-tool operation on the path (absent when the
 * file was never touched), `seq` the failed edit. shell-edit: `staleSeq` is the file-tool
 * operation that was tracking the file, `seq` the shell command that rewrote it. Any other
 * detector gets neutral words, never stamp-guard's. The chain and the sequence below it both
 * read these, so a call marked in the sequence carries the same word as its step in the chain.
 */
export function chainWords(f: FindingDto): ChainWords {
  switch (f.detector) {
    case 'stamp-guard': return { stale: 'stamped', cause: 'changed', finding: 'refused' };
    case 'edit-miss': return { stale: null, cause: missCauseWord(f.category), finding: 'edit missed' };
    case 'shell-edit': return { stale: 'tracked by a file tool', cause: null, finding: 'rewritten from the shell' };
    default: return { stale: 'earlier operation', cause: 'related event', finding: 'reported' };
  }
}

/** The seq timeline of one finding, in the words of the detector that produced it. */
export function chainSteps(f: FindingDto): ChainStep[] {
  const words = chainWords(f);
  const steps: ChainStep[] = [];
  const add = (seq: number | null, word: string | null, final = false): void => {
    if (seq !== null && word !== null) { steps.push({ seq, label: `${word} at ${seq}`, final }); }
  };
  add(f.staleSeq, words.stale);
  add(f.causeSeq, words.cause);
  add(f.seq, words.finding, true);
  return steps;
}

function missCauseWord(category: Category | null): string {
  switch (category) {
    case 'REPEATED_MISS': return 'missed before';
    case 'MISS_AFTER_EDIT': return 'own edit or write';
    case 'MISS_AFTER_READ': return 'read';
    case 'MISS_AFTER_PARTIAL_READ': return 'partial read';
    default: return 'previous operation';
  }
}
