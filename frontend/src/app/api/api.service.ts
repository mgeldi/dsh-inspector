import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import type { FilterValues } from '../state/filters';
import type {
  CohortPageDto, FindingDetailDto, FindingsPageDto, IndexSummaryDto, OverviewDto, SortDir, SortField,
} from './types';

export interface FindingsRequest {
  filters?: FilterValues;
  plane?: string; detector?: string; session?: string; code?: string;
  sort?: { field: SortField; dir: SortDir };
  page: number; size: number;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  overview(filters?: FilterValues): Observable<OverviewDto> {
    return this.http.get<OverviewDto>('/api/overview', { params: toParams(filters) });
  }

  findings(req: FindingsRequest): Observable<FindingsPageDto> {
    let params = toParams(req.filters);
    params = set(params, 'plane', req.plane);
    params = set(params, 'detector', req.detector);
    params = set(params, 'session', req.session);
    params = set(params, 'code', req.code);
    params = set(params, 'sort', req.sort ? `${req.sort.field}:${req.sort.dir}` : null);
    params = params.set('page', String(req.page)).set('size', String(req.size));
    return this.http.get<FindingsPageDto>('/api/findings', { params });
  }

  finding(id: number): Observable<FindingDetailDto> {
    return this.http.get<FindingDetailDto>(`/api/findings/${id}`);
  }

  cohorts(groupBy: string, baseline?: string, filters?: FilterValues): Observable<CohortPageDto> {
    let params = toParams(filters).set('groupBy', groupBy);
    if (baseline) { params = params.set('baseline', baseline); }
    return this.http.get<CohortPageDto>('/api/cohorts', { params });
  }

  runIndex(): Observable<IndexSummaryDto> {
    return this.http.post<IndexSummaryDto>('/api/index/run', {});
  }
}

function toParams(filters?: FilterValues): HttpParams {
  let p = new HttpParams();
  if (!filters) { return p; }
  p = set(p, 'from', filters.from == null ? null : String(filters.from));
  p = set(p, 'to', filters.to == null ? null : String(filters.to));
  for (const key of ['schema', 'model', 'preset', 'harnessVersion'] as const) {
    p = set(p, key, filters[key]);
  }
  return p;
}

/** The rule that keeps an unset filter out of the request: the backend treats '' as a value. */
function set(params: HttpParams, key: string, value: string | null | undefined): HttpParams {
  return value == null || value === '' ? params : params.set(key, value);
}
