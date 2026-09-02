import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, shareReplay, tap } from 'rxjs';
import {
  AppNotification,
  ClaimDetail,
  ClaimFilters,
  ClaimSummary,
  CompleteTaskRequest,
  CreateClaimRequest,
  DashboardStats,
  EngineStatus,
  InboxScope,
  PageResponse,
  ReferenceData,
  TaskCounts,
  TaskSummary,
  TypeSuggestion,
  UserSummary,
} from './models';

/** Every call to the backend goes through here. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  private referenceData$?: Observable<ReferenceData>;

  /** Unread notification badge, refreshed by the shell. */
  readonly unreadNotifications = signal(0);

  // ---------------------------------------------------------------- reference

  /** Enumerations are immutable for the life of the session, so they are cached. */
  referenceData(): Observable<ReferenceData> {
    this.referenceData$ ??= this.http
      .get<ReferenceData>('/api/reference-data')
      .pipe(shareReplay({ bufferSize: 1, refCount: false }));
    return this.referenceData$;
  }

  // ------------------------------------------------------------------- claims

  listClaims(filters: ClaimFilters): Observable<PageResponse<ClaimSummary>> {
    return this.http.get<PageResponse<ClaimSummary>>('/api/claims', {
      params: toParams(filters),
    });
  }

  getClaim(id: number): Observable<ClaimDetail> {
    return this.http.get<ClaimDetail>(`/api/claims/${id}`);
  }

  getClaimByReference(reference: string): Observable<ClaimDetail> {
    return this.http.get<ClaimDetail>(`/api/claims/by-reference/${reference}`);
  }

  createClaim(request: CreateClaimRequest): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>('/api/claims', request);
  }

  suggestType(subject: string, description: string): Observable<TypeSuggestion> {
    return this.http.post<TypeSuggestion>('/api/claims/suggest-type', { subject, description });
  }

  addComment(id: number, comment: string): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>(`/api/claims/${id}/comments`, { comment });
  }

  cancelClaim(id: number, reason: string): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>(`/api/claims/${id}/cancel`, { reason });
  }

  // -------------------------------------------------------------------- tasks

  listTasks(
    scope: InboxScope,
    step?: string | null,
    page = 0,
    size = 20,
  ): Observable<PageResponse<TaskSummary>> {
    return this.http.get<PageResponse<TaskSummary>>('/api/tasks', {
      params: toParams({ scope, step: step ?? undefined, page, size }),
    });
  }

  taskCounts(): Observable<TaskCounts> {
    return this.http.get<TaskCounts>('/api/tasks/counts');
  }

  getTask(taskKey: string): Observable<TaskSummary> {
    return this.http.get<TaskSummary>(`/api/tasks/${taskKey}`);
  }

  assignTask(taskKey: string): Observable<TaskSummary> {
    return this.http.post<TaskSummary>(`/api/tasks/${taskKey}/assign`, {});
  }

  releaseTask(taskKey: string): Observable<TaskSummary> {
    return this.http.post<TaskSummary>(`/api/tasks/${taskKey}/unassign`, {});
  }

  completeTask(taskKey: string, request: CompleteTaskRequest): Observable<ClaimSummary> {
    return this.http.post<ClaimSummary>(`/api/tasks/${taskKey}/complete`, request);
  }

  // ---------------------------------------------------------------- dashboard

  dashboard(days = 30): Observable<DashboardStats> {
    return this.http.get<DashboardStats>('/api/dashboard/stats', { params: toParams({ days }) });
  }

  // ------------------------------------------------------------ notifications

  notifications(limit = 30): Observable<AppNotification[]> {
    return this.http.get<AppNotification[]>('/api/notifications', { params: toParams({ limit }) });
  }

  refreshUnreadCount(): Observable<{ count: number }> {
    return this.http
      .get<{ count: number }>('/api/notifications/unread-count')
      .pipe(tap((result) => this.unreadNotifications.set(result.count)));
  }

  markNotificationRead(id: number): Observable<void> {
    return this.http.post<void>(`/api/notifications/${id}/read`, {});
  }

  markAllNotificationsRead(): Observable<{ updated: number }> {
    return this.http.post<{ updated: number }>('/api/notifications/read-all', {});
  }

  // -------------------------------------------------------------------- admin

  engineStatus(): Observable<EngineStatus> {
    return this.http.get<EngineStatus>('/api/admin/engine');
  }

  deployProcess(): Observable<{ bpmnProcessId: string; processDefinitionKey: string; version: number }> {
    return this.http.post<{ bpmnProcessId: string; processDefinitionKey: string; version: number }>(
      '/api/admin/engine/deploy',
      {},
    );
  }

  synchronise(): Observable<{ correctedClaims: number; breachedDeadlines: number }> {
    return this.http.post<{ correctedClaims: number; breachedDeadlines: number }>(
      '/api/admin/engine/synchronise',
      {},
    );
  }

  listUsers(): Observable<UserSummary[]> {
    return this.http.get<UserSummary[]>('/api/admin/users');
  }

  createUser(payload: Record<string, unknown>): Observable<UserSummary> {
    return this.http.post<UserSummary>('/api/admin/users', payload);
  }

  updateUser(id: number, payload: Record<string, unknown>): Observable<UserSummary> {
    return this.http.put<UserSummary>(`/api/admin/users/${id}`, payload);
  }

  // ------------------------------------------------------------------ exports

  /** Builds a download URL; the token is appended because downloads bypass the interceptor. */
  exportUrl(format: 'xlsx' | 'csv', filters: ClaimFilters): string {
    const params = toParams(filters).toString();
    return `/api/exports/claims.${format}${params ? `?${params}` : ''}`;
  }

  claimPdfUrl(id: number): string {
    return `/api/exports/claims/${id}.pdf`;
  }

  /** Downloads a protected file through the interceptor and hands it to the browser. */
  download(url: string, filename: string): Observable<Blob> {
    return this.http.get(url, { responseType: 'blob' }).pipe(
      tap((blob) => {
        const objectUrl = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = objectUrl;
        link.download = filename;
        link.click();
        URL.revokeObjectURL(objectUrl);
      }),
    );
  }

  // ------------------------------------------------------------------ assistant

  /** Whether the assistant is configured on the server, so the panel can hide itself. */
  assistantAvailable(): Observable<{ available: boolean }> {
    return this.http.get<{ available: boolean }>('/api/assistant/status');
  }

  /** Sends the whole conversation; the unit asking is taken from the session, not the body. */
  askAssistant(claimId: number, messages: AssistantTurn[]): Observable<{ reply: string }> {
    return this.http.post<{ reply: string }>(`/api/assistant/claims/${claimId}`, { messages });
  }
}

/** One turn of an assistant conversation. */
export interface AssistantTurn {
  fromUser: boolean;
  text: string;
}

/** Turns a filter object into query parameters, dropping empties and expanding arrays. */
function toParams(source: object): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(source as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    if (Array.isArray(value)) {
      for (const entry of value) {
        params = params.append(key, String(entry));
      }
    } else {
      params = params.set(key, String(value));
    }
  }
  return params;
}
