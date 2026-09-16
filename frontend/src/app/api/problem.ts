export interface ProblemDetail {
  type?: string; title?: string; status?: number; detail?: string; instance?: string;
  filter?: string; value?: string; allowed?: string[];
}

export function isProblem(body: unknown): body is ProblemDetail {
  return !!body && typeof body === 'object' && 'status' in (body as Record<string, unknown>);
}

/**
 * The one problem the backend reports that is not a mistake: the server is already doing what
 * was asked. It gets its own bar, phrased as a statement, because a red alert for "your request
 * is redundant" teaches people to ignore the alerts that mean something.
 */
export const INDEX_ALREADY_RUNNING = 'urn:dsh-inspector:index-already-running';

export function isIndexAlreadyRunning(body: unknown): body is ProblemDetail {
  return isProblem(body) && body.type === INDEX_ALREADY_RUNNING && body.status === 409;
}

/**
 * How many allowed values the bar can name before it stops being a sentence. Six is not a guess:
 * the largest vocabulary the rail can currently send is five values (models, on a 165-session
 * corpus), and the `sort` key list is exactly six — so nothing the interface itself filters by is
 * ever truncated. What the cap bounds is what the API allows: 20 codes, 165 session ids.
 */
const MAX_SHOWN = 6;

/**
 * One sentence a human can act on. `allowed` is not decoration: the rail can repair itself.
 *
 * <p>The list is truncated here, not on the server — the problem body keeps every value, because a
 * client that can act on a list is not reading this sentence. This is the one place the sentence
 * is written, and it renders into a single bar: an uncapped `join` turned a rejected session id
 * into a 6,700-character paragraph.
 */
export function describeProblem(p: ProblemDetail): string {
  if (p.allowed?.length) {
    const shown = p.allowed.slice(0, MAX_SHOWN).join(', ');
    const rest = p.allowed.length - MAX_SHOWN;
    const valid = rest > 0 ? `${shown}, and ${rest} more` : shown;
    return `${p.title ?? 'Rejected'}: '${p.value}' is not a valid ${p.filter}. Valid: ${valid}`;
  }
  return p.detail ?? p.title ?? `Request failed${p.status ? ` (${p.status})` : ''}`;
}
