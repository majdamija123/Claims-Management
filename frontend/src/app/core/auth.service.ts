import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { LoginResponse, UserSummary } from './models';

const TOKEN_KEY = 'cdg.claims.token';
const USER_KEY = 'cdg.claims.user';

/** Holds the session: the bearer token, the signed-in user and the role helpers. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly tokenSignal = signal<string | null>(localStorage.getItem(TOKEN_KEY));
  private readonly userSignal = signal<UserSummary | null>(readStoredUser());

  readonly user = this.userSignal.asReadonly();
  readonly isAuthenticated = computed(() => this.tokenSignal() !== null);
  readonly role = computed(() => this.userSignal()?.role ?? null);

  /** Administrators and supervisors see every queue and every complaint. */
  readonly isOversight = computed(() => {
    const role = this.role();
    return role === 'ADMIN' || role === 'SUPERVISOR';
  });

  readonly isAdmin = computed(() => this.role() === 'ADMIN');

  /** The step this user works on, or null for oversight roles. */
  readonly workflowStep = computed(() => this.userSignal()?.workflowStep ?? null);

  token(): string | null {
    return this.tokenSignal();
  }

  login(username: string, password: string): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>('/api/auth/login', { username, password })
      .pipe(tap((response) => this.store(response)));
  }

  /** Re-reads the profile; also confirms the stored token is still accepted. */
  refreshProfile(): Observable<UserSummary> {
    return this.http
      .get<UserSummary>('/api/auth/me')
      .pipe(tap((user) => this.setUser(user)));
  }

  logout(navigate = true): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.tokenSignal.set(null);
    this.userSignal.set(null);
    if (navigate) {
      void this.router.navigate(['/login']);
    }
  }

  private store(response: LoginResponse): void {
    localStorage.setItem(TOKEN_KEY, response.token);
    this.tokenSignal.set(response.token);
    this.setUser(response.user);
  }

  private setUser(user: UserSummary): void {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    this.userSignal.set(user);
  }
}

function readStoredUser(): UserSummary | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as UserSummary;
  } catch {
    localStorage.removeItem(USER_KEY);
    return null;
  }
}
