import { Injectable, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from './models';

export interface Toast {
  id: number;
  message: string;
  kind: 'info' | 'success' | 'warn' | 'error';
}

/** Small transient messages shown at the bottom-right of the shell. */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private counter = 0;
  private readonly items = signal<Toast[]>([]);
  readonly toasts = this.items.asReadonly();

  success(message: string): void {
    this.push(message, 'success');
  }

  info(message: string): void {
    this.push(message, 'info');
  }

  warn(message: string): void {
    this.push(message, 'warn');
  }

  error(message: string): void {
    this.push(message, 'error', 7000);
  }

  /** Turns a failed request into a readable message using the problem document. */
  fromHttp(error: unknown, fallback = 'The request could not be completed'): void {
    this.error(describeHttpError(error, fallback));
  }

  dismiss(id: number): void {
    this.items.update((list) => list.filter((toast) => toast.id !== id));
  }

  private push(message: string, kind: Toast['kind'], ttl = 4500): void {
    const id = ++this.counter;
    this.items.update((list) => [...list, { id, message, kind }]);
    setTimeout(() => this.dismiss(id), ttl);
  }
}

/** Extracts the most useful sentence from an API failure. */
export function describeHttpError(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'The server is unreachable. Check that the backend is running.';
    }
    const problem = error.error as ProblemDetail | undefined;
    if (problem?.fieldErrors) {
      const first = Object.entries(problem.fieldErrors)[0];
      if (first) {
        return `${first[0]}: ${first[1]}`;
      }
    }
    if (problem?.detail) {
      return problem.detail;
    }
    if (problem?.title) {
      return problem.title;
    }
  }
  return fallback;
}
