import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { ClaimDetail, ReferenceData, TaskSummary } from '../../core/models';
import { AssistantContextService } from '../../core/assistant-context.service';
import { ToastService } from '../../core/toast.service';
import { CHIPS } from '../../shared/chips';
import { DateTimePipe, RelativeTimePipe } from '../../shared/format';
import { CompleteTaskDialogComponent } from '../tasks/complete-task-dialog';

type Tab = 'overview' | 'timeline' | 'workflow';

/** Everything known about one complaint, including how far it has travelled. */
@Component({
  selector: 'app-claim-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    RouterLink,
    DateTimePipe,
    RelativeTimePipe,
    CompleteTaskDialogComponent,
    ...CHIPS,
  ],
  template: `
    <div class="page">
      @if (loading()) {
        <div class="loading-block"><div class="spinner lg"></div></div>
      } @else if (claim(); as detail) {
        <div class="page-head">
          <div>
            <div class="crumbs small muted">
              <a routerLink="/claims">Complaints</a> / {{ detail.summary.reference }}
            </div>
            <h1>{{ detail.summary.subject }}</h1>
            <div class="row head-chips">
              <app-status-chip [status]="detail.summary.status" [label]="detail.summary.statusLabel" />
              <app-priority-chip [priority]="detail.summary.priority" [label]="detail.summary.priorityLabel" />
              @if (detail.summary.currentStep) {
                <app-step-chip [step]="detail.summary.currentStep" [label]="detail.summary.currentStepLabel!" />
              }
              @if (!detail.summary.currentStep) {
                <span class="chip grey plain">Closed</span>
              } @else {
                <app-sla-chip [health]="detail.summary.slaHealth" />
              }
              @if (detail.returnCount > 0) {
                <span class="chip amber">Returned {{ detail.returnCount }}×</span>
              }
            </div>
          </div>
          <div class="row">
            <button class="btn secondary" (click)="downloadPdf(detail)">Download dossier</button>
            @if (auth.isAdmin() && !isClosed(detail)) {
              <button class="btn danger" (click)="cancelling.set(true)">Cancel complaint</button>
            }
          </div>
        </div>

        <!-- Progress through the five steps of the process -->
        <div class="card stepper">
          @for (step of steps(); track step.value) {
            <div
              class="step"
              [class.done]="stepState(detail, step.value) === 'done'"
              [class.current]="stepState(detail, step.value) === 'current'"
            >
              <div class="bullet">
                {{ stepState(detail, step.value) === 'done' ? '✓' : step.order }}
              </div>
              <div class="step-text">
                <div class="step-label">{{ step.label }}</div>
                @if (stepState(detail, step.value) === 'current') {
                  <div class="step-hint small">
                    @if (detail.summary.slaDueAt) {
                      Due {{ detail.summary.slaDueAt | relativeTime }}
                    } @else {
                      In progress
                    }
                  </div>
                }
              </div>
            </div>
          }
        </div>

        @if (detail.openTasks.length > 0) {
          <div class="card open-tasks">
            <div class="card-head"><h2>Waiting for action</h2></div>
            <div class="card-body tight">
              @for (task of detail.openTasks; track task.taskKey) {
                <div class="open-task">
                  <div>
                    <strong>{{ task.stepLabel }}</strong>
                    <span class="muted small">
                      · {{ task.assignee ? 'taken by ' + task.assignee : 'nobody has taken it yet' }}
                      @if (task.dueDate) {
                        · due {{ task.dueDate | relativeTime }}
                      }
                    </span>
                  </div>
                  <div class="spacer"></div>
                  @if (task.canAct) {
                    <button class="btn primary sm" (click)="activeTask.set(task)">Handle this step</button>
                  } @else {
                    <span class="muted small">Handled by the {{ task.stepLabel }} unit</span>
                  }
                </div>
              }
            </div>
          </div>
        }

        <div class="tabs">
          <button [class.active]="tab() === 'overview'" (click)="tab.set('overview')">Overview</button>
          <button [class.active]="tab() === 'timeline'" (click)="tab.set('timeline')">
            History ({{ detail.history.length }})
          </button>
          <button [class.active]="tab() === 'workflow'" (click)="tab.set('workflow')">Process data</button>
        </div>

        @switch (tab()) {
          @case ('overview') {
            <div class="grid cols-2">
              <div class="card">
                <div class="card-head"><h2>Complaint</h2></div>
                <div class="card-body">
                  <p class="description">{{ detail.description }}</p>
                  <dl>
                    <dt>Category</dt>
                    <dd>{{ detail.summary.typeLabel }}</dd>
                    <dt>Suggested by the model</dt>
                    <dd>
                      @if (detail.predictedTypeLabel) {
                        {{ detail.predictedTypeLabel }}
                        <span class="muted small">
                          ({{ percent(detail.predictionConfidence) }} confidence)
                        </span>
                        @if (detail.predictedType !== detail.summary.type) {
                          <span class="chip amber plain">corrected</span>
                        }
                      } @else {
                        <span class="muted">—</span>
                      }
                    </dd>
                    <dt>Channel</dt>
                    <dd>{{ detail.summary.channelLabel }}</dd>
                    <dt>Entity concerned</dt>
                    <dd>{{ detail.entity || '—' }}</dd>
                    <dt>Registered</dt>
                    <dd>{{ detail.summary.createdAt | dateTime }} by {{ detail.createdBy || 'system' }}</dd>
                    @if (detail.closedAt) {
                      <dt>Closed</dt>
                      <dd>{{ detail.closedAt | dateTime }}</dd>
                    }
                  </dl>
                </div>
              </div>

              <div class="card">
                <div class="card-head"><h2>Customer</h2></div>
                <div class="card-body">
                  <dl>
                    <dt>Name</dt>
                    <dd>{{ detail.summary.customerName }}</dd>
                    <dt>E-mail</dt>
                    <dd>{{ detail.customerEmail || '—' }}</dd>
                    <dt>Telephone</dt>
                    <dd>{{ detail.customerPhone || '—' }}</dd>
                    <dt>Account / file</dt>
                    <dd>{{ detail.customerReference || '—' }}</dd>
                  </dl>
                </div>
              </div>

              @if (detail.resolution) {
                <div class="card span-2">
                  <div class="card-head"><h2>Answer given to the customer</h2></div>
                  <div class="card-body"><p class="description">{{ detail.resolution }}</p></div>
                </div>
              }
              @if (detail.rejectionReason) {
                <div class="card span-2">
                  <div class="card-head"><h2>Reason for rejection</h2></div>
                  <div class="card-body"><p class="description">{{ detail.rejectionReason }}</p></div>
                </div>
              }

              <div class="card span-2">
                <div class="card-head"><h2>Add an internal note</h2></div>
                <div class="card-body">
                  <textarea
                    rows="3"
                    [(ngModel)]="comment"
                    placeholder="Visible to colleagues in the history, not to the customer."
                  ></textarea>
                  <div class="row end">
                    <button class="btn secondary" (click)="addComment(detail)" [disabled]="!comment.trim() || busy()">
                      Add note
                    </button>
                  </div>
                </div>
              </div>

            </div>
          }

          @case ('timeline') {
            <div class="card">
              <div class="card-body">
                <ol class="timeline">
                  @for (event of detail.history; track event.id) {
                    <li>
                      <div class="dot" [class]="toneOf(event.type)"></div>
                      <div class="event">
                        <div class="event-head">
                          <strong>{{ event.typeLabel }}</strong>
                          @if (event.stepLabel) {
                            <span class="chip grey plain">{{ event.stepLabel }}</span>
                          }
                          @if (event.decisionLabel) {
                            <span class="chip navy plain">{{ event.decisionLabel }}</span>
                          }
                          <span class="spacer"></span>
                          <span class="muted small nowrap">{{ event.occurredAt | dateTime }}</span>
                        </div>
                        <div class="muted small">
                          by {{ event.actor }}@if (event.actorRole) { ({{ event.actorRole }}) }
                        </div>
                        @if (event.comment) {
                          <div class="event-comment">{{ event.comment }}</div>
                        }
                      </div>
                    </li>
                  }
                </ol>
              </div>
            </div>
          }

          @case ('workflow') {
            <div class="card">
              <div class="card-head"><h2>Camunda process instance</h2></div>
              <div class="card-body">
                <dl>
                  <dt>Process instance key</dt>
                  <dd class="mono">{{ detail.processInstanceKey ?? '—' }}</dd>
                  <dt>Process definition version</dt>
                  <dd>{{ detail.processVersion ?? '—' }}</dd>
                  <dt>Step started</dt>
                  <dd>{{ detail.stepStartedAt | dateTime }}</dd>
                  <dt>Deadline of the current step</dt>
                  <dd>{{ detail.summary.slaDueAt | dateTime }}</dd>
                  <dt>Times returned by validation</dt>
                  <dd>{{ detail.returnCount }}</dd>
                </dl>

                <h3 class="vars-title">Process variables</h3>
                <div class="table-wrap">
                  <table class="data">
                    <thead>
                      <tr><th>Name</th><th>Value</th></tr>
                    </thead>
                    <tbody>
                      @for (entry of variables(detail); track entry[0]) {
                        <tr>
                          <td class="mono">{{ entry[0] }}</td>
                          <td class="truncate">{{ entry[1] }}</td>
                        </tr>
                      } @empty {
                        <tr><td colspan="2" class="muted">No variable available.</td></tr>
                      }
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          }
        }

        @if (cancelling()) {
          <div class="modal-backdrop" (click)="cancelling.set(false)">
            <div class="modal" (click)="$event.stopPropagation()">
              <div class="modal-head"><h2>Cancel {{ detail.summary.reference }}</h2></div>
              <div class="modal-body">
                <p class="muted small">
                  The Camunda process instance is terminated and the complaint is closed. This
                  cannot be undone.
                </p>
                <div class="field required">
                  <label for="reason">Reason</label>
                  <textarea id="reason" rows="3" [(ngModel)]="cancelReason"></textarea>
                </div>
              </div>
              <div class="modal-foot">
                <button class="btn secondary" (click)="cancelling.set(false)">Keep it open</button>
                <button class="btn danger" (click)="confirmCancel(detail)" [disabled]="!cancelReason.trim() || busy()">
                  Cancel the complaint
                </button>
              </div>
            </div>
          </div>
        }
      } @else {
        <div class="banner error">This complaint could not be loaded.</div>
      }
    </div>

    @if (activeTask(); as task) {
      <app-complete-task-dialog
        [task]="task"
        [draftAnswer]="draftAnswer()"
        (cancelled)="activeTask.set(null)"
        (completed)="onCompleted()"
      />
    }
  `,
  styles: [
    `
      .crumbs {
        margin-bottom: 4px;
      }
      .head-chips {
        margin-top: 9px;
      }
      .stepper {
        display: flex;
        padding: 16px 18px;
        gap: 8px;
        margin-bottom: 16px;
        overflow-x: auto;
      }
      .step {
        display: flex;
        align-items: center;
        gap: 9px;
        flex: 1;
        min-width: 150px;
        opacity: 0.5;
      }
      .step.done,
      .step.current {
        opacity: 1;
      }
      .bullet {
        width: 26px;
        height: 26px;
        border-radius: 50%;
        background: var(--ink-100);
        color: var(--ink-500);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 12px;
        font-weight: 600;
        flex: none;
      }
      .step.done .bullet {
        background: var(--green-100);
        color: var(--green-600);
      }
      .step.current .bullet {
        background: var(--cdg-green-700);
        color: #fff;
      }
      .step-label {
        font-size: 13px;
        font-weight: 500;
      }
      .step-hint {
        color: var(--ink-500);
      }
      .open-tasks {
        margin-bottom: 16px;
        border-left: 3px solid var(--cdg-green-700);
      }
      .open-task {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 8px 0;
      }
      .span-2 {
        grid-column: 1 / -1;
      }
      .description {
        white-space: pre-wrap;
        line-height: 1.65;
        margin-bottom: 14px;
      }
      dl {
        display: grid;
        grid-template-columns: minmax(120px, 38%) 1fr;
        gap: 8px 14px;
        margin: 0;
        font-size: 13px;
      }
      dt {
        color: var(--ink-500);
      }
      dd {
        margin: 0;
      }
      .vars-title {
        margin: 20px 0 10px;
      }
      .timeline {
        list-style: none;
        margin: 0;
        padding: 0;
      }
      .timeline li {
        display: flex;
        gap: 14px;
        padding-bottom: 18px;
        position: relative;
      }
      .timeline li:not(:last-child)::before {
        content: '';
        position: absolute;
        left: 5px;
        top: 16px;
        bottom: 0;
        width: 2px;
        background: var(--ink-200);
      }
      .dot {
        width: 12px;
        height: 12px;
        border-radius: 50%;
        background: var(--cdg-green-600);
        margin-top: 4px;
        flex: none;
        z-index: 1;
      }
      .dot.green {
        background: var(--green-600);
      }
      .dot.red {
        background: var(--red-600);
      }
      .dot.amber {
        background: var(--amber-600);
      }
      .dot.grey {
        background: var(--ink-400);
      }
      .event {
        flex: 1;
        min-width: 0;
      }
      .event-head {
        display: flex;
        align-items: center;
        gap: 8px;
        flex-wrap: wrap;
      }
      .event-comment {
        margin-top: 6px;
        padding: 9px 11px;
        background: var(--ink-050);
        border-radius: var(--radius-sm);
        font-size: 13px;
        white-space: pre-wrap;
      }
    `,
  ],
})
export class ClaimDetailComponent implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly toasts = inject(ToastService);
  private readonly assistantContext = inject(AssistantContextService);
  readonly auth = inject(AuthService);

  readonly claim = signal<ClaimDetail | null>(null);
  readonly reference = signal<ReferenceData | null>(null);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly tab = signal<Tab>('overview');
  readonly activeTask = signal<TaskSummary | null>(null);
  readonly cancelling = signal(false);

  comment = '';
  cancelReason = '';

  /** Filled by the floating assistant; seeds the answer field of the completion dialog. */
  readonly draftAnswer = this.assistantContext.draftAnswer;

  ngOnInit(): void {
    this.api.referenceData().subscribe({ next: (data) => this.reference.set(data) });
    this.route.paramMap.subscribe((params) => {
      const id = Number(params.get('id'));
      if (Number.isFinite(id)) {
        this.load(id);
      }
    });
  }

  steps(): { value: string; label: string; order: number }[] {
    return this.reference()?.steps ?? [];
  }

  /** Where the complaint stands relative to a given step of the process. */
  stepState(detail: ClaimDetail, step: string): 'done' | 'current' | 'todo' {
    const current = detail.summary.currentStep;
    const order = this.steps().find((option) => option.value === step)?.order ?? 0;

    if (!current) {
      // Closed: every step it actually reached counts as done.
      return detail.summary.status === 'RESOLVED' ? 'done' : 'todo';
    }
    const currentOrder = this.steps().find((option) => option.value === current)?.order ?? 0;
    if (order < currentOrder) {
      return 'done';
    }
    return order === currentOrder ? 'current' : 'todo';
  }

  isClosed(detail: ClaimDetail): boolean {
    return ['RESOLVED', 'REJECTED', 'CANCELLED'].includes(detail.summary.status);
  }

  percent(value: number | undefined): string {
    return value === undefined || value === null ? '—' : `${Math.round(value * 100)}%`;
  }

  variables(detail: ClaimDetail): [string, string][] {
    return Object.entries(detail.processVariables ?? {})
      .map(([key, value]) => [key, formatValue(value)] as [string, string])
      .sort((a, b) => a[0].localeCompare(b[0]));
  }

  toneOf(type: string): string {
    switch (type) {
      case 'RESOLVED':
        return 'green';
      case 'REJECTED':
      case 'CANCELLED':
      case 'SLA_BREACHED':
        return 'red';
      case 'ESCALATED':
      case 'RETURNED':
        return 'amber';
      case 'COMMENT':
      case 'NOTIFIED':
        return 'grey';
      default:
        return '';
    }
  }

  addComment(detail: ClaimDetail): void {
    this.busy.set(true);
    this.api.addComment(detail.summary.id, this.comment.trim()).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.comment = '';
        this.claim.set(updated);
        this.toasts.success('Note added');
      },
      error: (error) => {
        this.busy.set(false);
        this.toasts.fromHttp(error, 'The note could not be added');
      },
    });
  }

  confirmCancel(detail: ClaimDetail): void {
    this.busy.set(true);
    this.api.cancelClaim(detail.summary.id, this.cancelReason.trim()).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.cancelling.set(false);
        this.cancelReason = '';
        this.claim.set(updated);
        this.toasts.warn(`${updated.summary.reference} cancelled`);
      },
      error: (error) => {
        this.busy.set(false);
        this.toasts.fromHttp(error, 'The complaint could not be cancelled');
      },
    });
  }

  downloadPdf(detail: ClaimDetail): void {
    this.api
      .download(this.api.claimPdfUrl(detail.summary.id), `${detail.summary.reference}.pdf`)
      .subscribe({
        next: () => this.toasts.success('Dossier downloaded'),
        error: (error) => this.toasts.fromHttp(error, 'The dossier could not be generated'),
      });
  }

  ngOnDestroy(): void {
    const id = this.claim()?.summary.id;
    if (id) {
      this.assistantContext.close(id);
    }
  }

  onCompleted(): void {
    this.activeTask.set(null);
    const id = this.claim()?.summary.id;
    if (id) {
      this.load(id);
    }
  }

  private load(id: number): void {
    this.loading.set(true);
    this.api.getClaim(id).subscribe({
      next: (detail) => {
        this.claim.set(detail);
        this.loading.set(false);
        this.assistantContext.open({
          id: detail.summary.id,
          reference: detail.summary.reference,
        });
      },
      error: (error) => {
        this.loading.set(false);
        this.toasts.fromHttp(error, 'This complaint could not be loaded');
      },
    });
  }
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
