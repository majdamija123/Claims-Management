import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { InboxScope, PageResponse, TaskSummary } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { CHIPS } from '../../shared/chips';
import { DateTimePipe, RelativeTimePipe } from '../../shared/format';
import { CompleteTaskDialogComponent } from './complete-task-dialog';

interface ScopeTab {
  scope: InboxScope;
  label: string;
  hint: string;
}

/** The working screen: what this user has to do, and what their unit has waiting. */
@Component({
  selector: 'app-task-inbox',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, DateTimePipe, RelativeTimePipe, CompleteTaskDialogComponent, ...CHIPS],
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>My tasks</h1>
          <div class="subtitle">{{ scopeHint() }}</div>
        </div>
        <button class="btn secondary" (click)="load()" [disabled]="loading()">Refresh</button>
      </div>

      <div class="tabs">
        @for (tab of tabs; track tab.scope) {
          <button [class.active]="scope() === tab.scope" (click)="changeScope(tab.scope)">
            {{ tab.label }}
          </button>
        }
      </div>

      @if (loading()) {
        <div class="loading-block"><div class="spinner lg"></div></div>
      } @else if (page(); as result) {
        @if (result.content.length === 0) {
          <div class="card">
            <div class="empty">
              <div class="icon">✓</div>
              {{ emptyMessage() }}
            </div>
          </div>
        } @else {
          <div class="task-list">
            @for (task of result.content; track task.taskKey) {
              <article class="card task" [class.overdue]="task.overdue">
                <div class="task-main">
                  <div class="task-head">
                    <app-step-chip [step]="task.step" [label]="task.stepLabel" />
                    @if (task.claim; as claim) {
                      <a [routerLink]="['/claims', claim.id]" class="reference mono">
                        {{ claim.reference }}
                      </a>
                      <app-priority-chip [priority]="claim.priority" [label]="claim.priorityLabel" />
                    }
                    @if (task.overdue) {
                      <span class="chip red">Overdue</span>
                    }
                    @if (task.assignee) {
                      <span class="chip grey plain">
                        {{ task.assignee === username() ? 'Taken by you' : 'Taken by ' + task.assignee }}
                      </span>
                    }
                  </div>

                  @if (task.claim; as claim) {
                    <h3>{{ claim.subject }}</h3>
                    <div class="task-meta muted small">
                      {{ claim.customerName }} · {{ claim.typeLabel }} · via {{ claim.channelLabel }}
                    </div>
                  } @else {
                    <h3 class="muted">Complaint not found for this task</h3>
                  }

                  <div class="task-dates small muted">
                    <span>Waiting since {{ task.createdAt | dateTime }}</span>
                    @if (task.dueDate) {
                      <span [class.late]="task.overdue">
                        · Due {{ task.dueDate | relativeTime }}
                      </span>
                    }
                  </div>
                </div>

                <div class="task-actions">
                  @if (scope() === 'COMPLETED') {
                    <span class="chip green">Completed</span>
                  } @else if (!task.canAct) {
                    <span class="muted small">Handled by another unit</span>
                  } @else {
                    @if (!task.assignee) {
                      <button class="btn secondary sm" (click)="take(task)" [disabled]="busyKey() === task.taskKey">
                        Take
                      </button>
                    } @else if (task.assignee === username()) {
                      <button class="btn ghost sm" (click)="release(task)" [disabled]="busyKey() === task.taskKey">
                        Release
                      </button>
                    }
                    <button class="btn primary sm" (click)="open(task)">Handle</button>
                  }
                </div>
              </article>
            }
          </div>

          @if (result.totalPages > 1) {
            <div class="row end pager">
              <button class="btn secondary sm" [disabled]="result.page === 0" (click)="go(result.page - 1)">
                Previous
              </button>
              <span class="small muted">Page {{ result.page + 1 }} of {{ result.totalPages }}</span>
              <button
                class="btn secondary sm"
                [disabled]="result.page + 1 >= result.totalPages"
                (click)="go(result.page + 1)"
              >
                Next
              </button>
            </div>
          }
        }
      }
    </div>

    @if (active(); as task) {
      <app-complete-task-dialog
        [task]="task"
        (cancelled)="active.set(null)"
        (completed)="onCompleted()"
      />
    }
  `,
  styles: [
    `
      .task-list {
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
      .task {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 14px 18px;
      }
      .task.overdue {
        border-left: 3px solid var(--red-600);
      }
      .task-main {
        flex: 1;
        min-width: 0;
      }
      .task-head {
        display: flex;
        align-items: center;
        gap: 8px;
        flex-wrap: wrap;
        margin-bottom: 7px;
      }
      .reference {
        font-weight: 600;
        font-size: 12.5px;
      }
      h3 {
        font-size: 14.5px;
        margin-bottom: 3px;
      }
      .task-meta {
        margin-bottom: 3px;
      }
      .task-dates .late {
        color: var(--red-600);
        font-weight: 600;
      }
      .task-actions {
        display: flex;
        gap: 8px;
        align-items: center;
        flex: none;
      }
      .pager {
        margin-top: 14px;
        align-items: center;
      }
      @media (max-width: 720px) {
        .task {
          flex-direction: column;
          align-items: stretch;
        }
        .task-actions {
          justify-content: flex-end;
        }
      }
    `,
  ],
})
export class TaskInboxComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly auth = inject(AuthService);

  readonly tabs: ScopeTab[] = [
    { scope: 'AVAILABLE', label: 'Available', hint: 'Unassigned tasks waiting in your unit’s queue' },
    { scope: 'MINE', label: 'Taken by me', hint: 'Tasks you have taken and not yet finished' },
    { scope: 'GROUP', label: 'Whole unit', hint: 'Every open task of your unit, taken or not' },
    { scope: 'COMPLETED', label: 'Done by me', hint: 'Tasks you have already completed' },
  ];

  readonly scope = signal<InboxScope>('AVAILABLE');
  readonly page = signal<PageResponse<TaskSummary> | null>(null);
  readonly loading = signal(false);
  readonly busyKey = signal<string | null>(null);
  readonly active = signal<TaskSummary | null>(null);
  private readonly pageIndex = signal(0);

  ngOnInit(): void {
    this.load();
  }

  username(): string {
    return this.auth.user()?.username ?? '';
  }

  scopeHint(): string {
    return this.tabs.find((tab) => tab.scope === this.scope())?.hint ?? '';
  }

  emptyMessage(): string {
    switch (this.scope()) {
      case 'MINE':
        return 'You have not taken any task. Look at the Available tab.';
      case 'COMPLETED':
        return 'You have not completed any task yet.';
      case 'GROUP':
        return 'Your unit has no open task right now.';
      default:
        return 'Nothing is waiting for your unit. Well done.';
    }
  }

  changeScope(scope: InboxScope): void {
    this.scope.set(scope);
    this.pageIndex.set(0);
    this.load();
  }

  go(page: number): void {
    this.pageIndex.set(page);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.listTasks(this.scope(), null, this.pageIndex(), 20).subscribe({
      next: (result) => {
        this.page.set(result);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.toasts.fromHttp(error, 'The task list could not be loaded');
      },
    });
  }

  take(task: TaskSummary): void {
    this.busyKey.set(task.taskKey);
    this.api.assignTask(task.taskKey).subscribe({
      next: () => {
        this.busyKey.set(null);
        this.toasts.success('Task taken');
        this.load();
      },
      error: (error) => {
        this.busyKey.set(null);
        this.toasts.fromHttp(error, 'The task could not be taken');
      },
    });
  }

  release(task: TaskSummary): void {
    this.busyKey.set(task.taskKey);
    this.api.releaseTask(task.taskKey).subscribe({
      next: () => {
        this.busyKey.set(null);
        this.toasts.info('Task returned to the queue');
        this.load();
      },
      error: (error) => {
        this.busyKey.set(null);
        this.toasts.fromHttp(error, 'The task could not be released');
      },
    });
  }

  open(task: TaskSummary): void {
    this.active.set(task);
  }

  onCompleted(): void {
    this.active.set(null);
    this.load();
  }
}
