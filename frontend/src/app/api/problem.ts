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

/** One sentence a human can act on. `allowed` is not decoration: the rail can repair itself. */
export function describeProblem(p: ProblemDetail): string {
  if (p.allowed?.length) {
    return `${p.title ?? 'Rejected'}: '${p.value}' is not a valid ${p.filter}. Valid: ${p.allowed.join(', ')}`;
  }
  return p.detail ?? p.title ?? `Request failed${p.status ? ` (${p.status})` : ''}`;
}
