export interface ProblemDetail {
  type?: string; title?: string; status?: number; detail?: string; instance?: string;
  filter?: string; value?: string; allowed?: string[];
}

export function isProblem(body: unknown): body is ProblemDetail {
  return !!body && typeof body === 'object' && 'status' in (body as Record<string, unknown>);
}

/** One sentence a human can act on. `allowed` is not decoration: the rail can repair itself. */
export function describeProblem(p: ProblemDetail): string {
  if (p.allowed?.length) {
    return `${p.title ?? 'Rejected'}: '${p.value}' is not a valid ${p.filter}. Valid: ${p.allowed.join(', ')}`;
  }
  return p.detail ?? p.title ?? `Request failed${p.status ? ` (${p.status})` : ''}`;
}
