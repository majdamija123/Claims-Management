import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { CompleteTaskRequest, Option, ReferenceData, TaskSummary } from '../../core/models';
import { ToastService } from '../../core/toast.service';

/**
 * The form an agent fills in to finish a task.
 *
 * <p>The available decisions come from the backend, which derives them from the process
 * model — so the buttons offered here can never diverge from what the engine accepts.
 */
@Component({
  selector: 'app-complete-task-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <div class="modal-backdrop" (click)="cancelled.emit()">
      <div class="modal" (click)="$event.stopPropagation()">
        <div class="modal-head">
          <h2>{{ task().stepLabel }}</h2>
          <div class="muted small">
            {{ task().claim?.reference }} — {{ task().claim?.subject }}
          </div>
        </div>

        <div class="modal-body">
          <div class="field required">
            <label>What is your decision?</label>
            <div class="decisions">
              @for (option of task().decisions; track option.value) {
                <button
                  type="button"
                  class="decision"
                  [class.selected]="decision() === option.value"
                  [class.destructive]="isDestructive(option.value)"
                  (click)="decision.set(option.value)"
                >
                  <span class="decision-title">{{ option.label }}</span>
                  <span class="decision-hint">{{ hintFor(option.value) }}</span>
                </button>
              }
            </div>
          </div>

          @if (isQualification()) {
            <div class="grid cols-2">
              <div class="field">
                <label for="type">Category (correct if needed)</label>
                <select id="type" name="type" [(ngModel)]="type">
                  <option value="">Keep {{ task().claim?.typeLabel }}</option>
                  @for (option of reference()?.claimTypes ?? []; track option.value) {
                    <option [value]="option.value">{{ option.label }}</option>
                  }
                </select>
              </div>
              <div class="field">
                <label for="priority">Urgency (correct if needed)</label>
                <select id="priority" name="priority" [(ngModel)]="priority">
                  <option value="">Keep {{ task().claim?.priorityLabel }}</option>
                  @for (option of reference()?.priorities ?? []; track option.value) {
                    <option [value]="option.value">{{ option.label }}</option>
                  }
                </select>
              </div>
            </div>
          }

          @if (needsResolution()) {
            <div class="field required">
              <label for="resolution">Answer to the customer</label>
              <textarea
                id="resolution"
                name="resolution"
                rows="5"
                [(ngModel)]="resolution"
                placeholder="This text is what the customer receives once validation approves it."
              ></textarea>
              <span class="hint">Sent to the customer after the validation step approves it.</span>
            </div>
          }

          @if (needsRejectionReason()) {
            <div class="field required">
              <label for="rejection">Reason for rejection</label>
              <textarea
                id="rejection"
                name="rejection"
                rows="4"
                [(ngModel)]="rejectionReason"
                placeholder="Why is this complaint not admissible?"
              ></textarea>
              <span class="hint">The customer is notified with this explanation.</span>
            </div>
          }

          <div class="field">
            <label for="comment">Internal note</label>
            <textarea
              id="comment"
              name="comment"
              rows="3"
              [(ngModel)]="comment"
              placeholder="Visible to colleagues in the complaint history, not to the customer."
            ></textarea>
          </div>

          @if (error()) {
            <div class="banner error">{{ error() }}</div>
          }
        </div>

        <div class="modal-foot">
          <button class="btn secondary" (click)="cancelled.emit()" [disabled]="busy()">Cancel</button>
          <button class="btn primary" (click)="submit()" [disabled]="busy() || !decision()">
            @if (busy()) {
              <span class="spinner"></span>
            }
            Confirm
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .decisions {
        display: flex;
        gap: 10px;
        flex-wrap: wrap;
      }
      .decision {
        flex: 1 1 200px;
        text-align: left;
        border: 1px solid var(--ink-300);
        background: var(--white);
        border-radius: var(--radius);
        padding: 11px 13px;
        cursor: pointer;
        font: inherit;
        display: flex;
        flex-direction: column;
        gap: 3px;
        transition: border-color 0.14s ease, background 0.14s ease;
      }
      .decision:hover {
        border-color: var(--cdg-green-600);
      }
      .decision.selected {
        border-color: var(--cdg-green-700);
        background: var(--cdg-green-050);
        box-shadow: 0 0 0 3px var(--cdg-green-100);
      }
      .decision.destructive.selected {
        border-color: var(--red-600);
        background: var(--red-100);
        box-shadow: none;
      }
      .decision-title {
        font-weight: 600;
        font-size: 13.5px;
      }
      .decision-hint {
        font-size: 12px;
        color: var(--ink-500);
      }
    `,
  ],
})
export class CompleteTaskDialogComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);

  readonly task = input.required<TaskSummary>();
  /** A draft carried over from the assistant, used only to seed the answer field. */
  readonly draftAnswer = input<string>('');

  readonly completed = output<void>();
  readonly cancelled = output<void>();

  readonly decision = signal<string | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly reference = signal<ReferenceData | null>(null);

  comment = '';
  resolution = '';
  rejectionReason = '';
  type = '';
  priority = '';

  readonly isQualification = computed(() => this.task().step === 'QUALIFICATION');
  readonly needsResolution = computed(() => this.decision() === 'ANSWER');
  readonly needsRejectionReason = computed(() => this.decision() === 'REJECT');

  ngOnInit(): void {
    this.api.referenceData().subscribe({
      next: (data) => this.reference.set(data),
      error: () => undefined,
    });
    // With a single possible decision there is nothing to choose: pre-select it.
    if (this.draftAnswer()) {
      this.resolution = this.draftAnswer();
    }

    const options: Option[] = this.task().decisions;
    if (options.length === 1) {
      this.decision.set(options[0].value);
    }
  }

  isDestructive(value: string): boolean {
    return value === 'REJECT' || value === 'RETURN';
  }

  hintFor(value: string): string {
    switch (value) {
      case 'VALIDATE':
        return 'Admissible — send it to the Front Office';
      case 'REJECT':
        return 'Not admissible — close and notify the customer';
      case 'ANSWER':
        return 'Your unit can answer — send it to validation';
      case 'ESCALATE':
        return 'Your unit cannot answer — pass it to the next one';
      case 'APPROVE':
        return 'Answer is correct — notify the customer and close';
      case 'RETURN':
        return 'Send it back to qualification for rework';
      default:
        return '';
    }
  }

  submit(): void {
    const decision = this.decision();
    if (!decision || this.busy()) {
      return;
    }
    if (this.needsResolution() && !this.resolution.trim()) {
      this.error.set('An answer to the customer is required.');
      return;
    }
    if (this.needsRejectionReason() && !this.rejectionReason.trim()) {
      this.error.set('A rejection reason is required.');
      return;
    }

    const request: CompleteTaskRequest = {
      decision,
      comment: this.comment.trim() || undefined,
      resolution: this.resolution.trim() || undefined,
      rejectionReason: this.rejectionReason.trim() || undefined,
      type: this.isQualification() && this.type ? this.type : undefined,
      priority: this.isQualification() && this.priority ? this.priority : undefined,
    };

    this.busy.set(true);
    this.error.set(null);

    this.api.completeTask(this.task().taskKey, request).subscribe({
      next: (claim) => {
        this.busy.set(false);
        this.toasts.success(
          `${claim.reference} — ${claim.statusLabel}${
            claim.currentStepLabel ? ' (' + claim.currentStepLabel + ')' : ''
          }`,
        );
        this.completed.emit();
      },
      error: (error) => {
        this.busy.set(false);
        this.error.set(
          (error?.error?.detail as string) ?? 'The task could not be completed.',
        );
      },
    });
  }
}
