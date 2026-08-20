import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, Subject, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { CreateClaimRequest, ReferenceData, TypeSuggestion } from '../../core/models';
import { ToastService, describeHttpError } from '../../core/toast.service';

/**
 * Registration form. As soon as the subject and description are typed, the classification
 * model is asked for a category; the agent keeps the last word on it.
 */
@Component({
  selector: 'app-claim-create',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page narrow">
      <div class="page-head">
        <div>
          <h1>Register a complaint</h1>
          <div class="subtitle">
            A process instance is started immediately and the qualification unit is notified.
          </div>
        </div>
        <a routerLink="/claims" class="btn secondary">Cancel</a>
      </div>

      <form #form="ngForm" (ngSubmit)="submit(form)">
        @if (error()) {
          <div class="banner error">{{ error() }}</div>
        }

        <div class="card">
          <div class="card-head"><h2>Customer</h2></div>
          <div class="card-body">
            <div class="grid cols-2">
              <div class="field required">
                <label for="customerName">Full name</label>
                <input id="customerName" name="customerName" [(ngModel)]="model.customerName" required maxlength="150" />
              </div>
              <div class="field">
                <label for="customerReference">Account / file number</label>
                <input id="customerReference" name="customerReference" [(ngModel)]="model.customerReference" maxlength="60" />
              </div>
              <div class="field">
                <label for="customerEmail">E-mail</label>
                <input id="customerEmail" name="customerEmail" type="email" [(ngModel)]="model.customerEmail" maxlength="150" />
                <span class="hint">Used to notify the customer when the complaint is closed.</span>
              </div>
              <div class="field">
                <label for="customerPhone">Telephone</label>
                <input id="customerPhone" name="customerPhone" type="tel" [(ngModel)]="model.customerPhone" maxlength="40" />
              </div>
              <div class="field required">
                <label for="channel">How did it arrive?</label>
                <select id="channel" name="channel" [(ngModel)]="model.channel" required>
                  @for (option of reference()?.channels ?? []; track option.value) {
                    <option [value]="option.value">{{ option.label }}</option>
                  }
                </select>
              </div>
              <div class="field">
                <label for="entity">Entity or branch concerned</label>
                <input id="entity" name="entity" [(ngModel)]="model.entity" maxlength="120" />
              </div>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-head"><h2>Complaint</h2></div>
          <div class="card-body">
            <div class="field required">
              <label for="subject">Subject</label>
              <input
                id="subject"
                name="subject"
                [(ngModel)]="model.subject"
                (ngModelChange)="onTextChanged()"
                required
                maxlength="250"
                placeholder="Summarise the complaint in one line"
              />
            </div>

            <div class="field required">
              <label for="description">Description</label>
              <textarea
                id="description"
                name="description"
                rows="6"
                [(ngModel)]="model.description"
                (ngModelChange)="onTextChanged()"
                required
                maxlength="4000"
                placeholder="What happened, when, and what does the customer expect?"
              ></textarea>
            </div>

            @if (suggestion(); as hint) {
              <div class="banner info suggestion">
                <div>
                  <strong>Suggested category: {{ hint.typeLabel }}</strong>
                  <span class="muted small">
                    ({{ hint.source === 'MODEL' ? 'classification model' : 'keyword rules' }},
                    confidence {{ percent(hint.confidence) }})
                  </span>
                  @if (hint.alternatives.length > 0) {
                    <div class="small muted alternatives">
                      Also possible:
                      @for (alt of hint.alternatives; track alt.type) {
                        <button type="button" class="link" (click)="model.type = alt.type">
                          {{ alt.typeLabel }}
                        </button>
                      }
                    </div>
                  }
                </div>
                <div class="spacer"></div>
                @if (model.type !== hint.type) {
                  <button type="button" class="btn secondary sm" (click)="model.type = hint.type">
                    Use it
                  </button>
                }
              </div>
            }

            <div class="grid cols-2">
              <div class="field required">
                <label for="type">Category</label>
                <select id="type" name="type" [(ngModel)]="model.type" required>
                  <option value="">Choose a category…</option>
                  @for (option of reference()?.claimTypes ?? []; track option.value) {
                    <option [value]="option.value">{{ option.label }}</option>
                  }
                </select>
              </div>
              <div class="field required">
                <label for="priority">Urgency</label>
                <select id="priority" name="priority" [(ngModel)]="model.priority" required>
                  @for (option of reference()?.priorities ?? []; track option.value) {
                    <option [value]="option.value">{{ option.label }}</option>
                  }
                </select>
                <span class="hint">Urgent complaints get proportionally shorter deadlines.</span>
              </div>
            </div>
          </div>
        </div>

        <div class="row end actions">
          <a routerLink="/claims" class="btn secondary">Cancel</a>
          <button class="btn primary" type="submit" [disabled]="busy() || !form.valid">
            @if (busy()) {
              <span class="spinner"></span>
            }
            Register and start the process
          </button>
        </div>
      </form>
    </div>
  `,
  styles: [
    `
      .narrow {
        max-width: 940px;
      }
      .card {
        margin-bottom: 16px;
      }
      .suggestion {
        display: flex;
        align-items: center;
        gap: 12px;
      }
      .alternatives {
        margin-top: 3px;
      }
      .link {
        border: none;
        background: none;
        padding: 0 4px;
        font: inherit;
        font-size: 12.5px;
        color: var(--navy-600);
        cursor: pointer;
        text-decoration: underline;
      }
      .actions {
        margin-bottom: 32px;
      }
    `,
  ],
})
export class ClaimCreateComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);

  private readonly textChanges = new Subject<void>();

  readonly reference = signal<ReferenceData | null>(null);
  readonly suggestion = signal<TypeSuggestion | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  model: CreateClaimRequest = {
    customerName: '',
    channel: 'EMAIL',
    subject: '',
    description: '',
    type: '',
    priority: 'NORMAL',
  };

  ngOnInit(): void {
    this.api.referenceData().subscribe({ next: (data) => this.reference.set(data) });

    this.textChanges
      .pipe(
        debounceTime(600),
        switchMap(() => this.api.suggestType(this.model.subject, this.model.description)),
      )
      .subscribe({
        next: (suggestion) => {
          this.suggestion.set(suggestion);
          // Only pre-fill an empty field: never overwrite the agent's own choice.
          if (!this.model.type) {
            this.model.type = suggestion.type;
          }
        },
        error: () => this.suggestion.set(null),
      });
  }

  onTextChanged(): void {
    if (this.model.subject.trim().length + this.model.description.trim().length >= 15) {
      this.textChanges.next();
    }
  }

  percent(value: number): string {
    return `${Math.round(value * 100)}%`;
  }

  submit(form: NgForm): void {
    if (this.busy() || !form.valid) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);

    this.api.createClaim({ ...this.model, type: this.model.type || undefined }).subscribe({
      next: (claim) => {
        this.busy.set(false);
        this.toasts.success(`${claim.summary.reference} registered`);
        void this.router.navigate(['/claims', claim.summary.id]);
      },
      error: (error) => {
        this.busy.set(false);
        this.error.set(describeHttpError(error, 'The complaint could not be registered'));
      },
    });
  }
}
