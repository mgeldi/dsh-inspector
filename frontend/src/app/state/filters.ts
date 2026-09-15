// Task 3 needs only the shape of the shared filter values (the backend's InsightFilter:
// from/to epoch millis, plus schema, model, preset, harnessVersion). The rail logic and
// its spec are Task 4's; they build on this type.
export interface FilterValues {
  from?: number | null;
  to?: number | null;
  schema?: string;
  model?: string;
  preset?: string;
  harnessVersion?: string;
}
