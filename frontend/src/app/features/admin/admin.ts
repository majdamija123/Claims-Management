import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { EngineStatus, ReferenceData, UserSummary } from '../../core/models';
import { ToastService } from '../../core/toast.service';

/** Administration: the state of the engine, the process model, and the accounts. */
@Component({
  selector: 'app-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Administration</h1>
          <div class="subtitle">Workflow engine and user accounts</div>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <h2>Workflow engine</h2>
          <button class="btn secondary sm" (click)="loadEngine()">Refresh</button>
        </div>
        <div class="card-body">
          @if (engine(); as status) {
            @if (status.simulated) {
              <div class="banner warn">
                <div>
                  <strong>Running on the built-in simulator.</strong>
                  No Camunda cluster is connected, so the process is replayed in memory. Every
                  screen works, but nothing appears in Operate or Tasklist. Set
                  <code>cdg.camunda.enabled=true</code> with your cluster credentials to connect
                  the real engine.
                </div>
              </div>
            } @else {
              <div class="banner success">
                <strong>Connected.</strong> {{ status.description }}
              </div>
            }

            <dl>
              <dt>Process id</dt>
              <dd class="mono">{{ status.processId }}</dd>
              <dt>Connection</dt>
              <dd>{{ status.description }}</dd>
              <dt>Deploy the model at startup</dt>
              <dd>{{ status.deployOnStartup ? 'Yes' : 'No' }}</dd>
              <dt>Last deployment from this screen</dt>
              <dd>{{ status.lastDeployment ?? '—' }}</dd>
            </dl>

            <div class="row actions">
              <button class="btn primary" (click)="deploy()" [disabled]="busy()">
                Deploy the BPMN model
              </button>
              <button class="btn secondary" (click)="synchronise()" [disabled]="busy()">
                Reconcile with the engine
              </button>
              <a class="btn ghost" href="/api/admin/engine/model" target="_blank" rel="noopener">
                View the BPMN source
              </a>
            </div>
          } @else {
            <div class="loading-block"><div class="spinner"></div></div>
          }
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <h2>Users</h2>
          <button class="btn primary sm" (click)="creating.set(!creating())">
            {{ creating() ? 'Close' : '+ New user' }}
          </button>
        </div>

        @if (creating()) {
          <div class="card-body new-user">
            <form #form="ngForm" (ngSubmit)="createUser(form)">
              <div class="grid cols-3">
                <div class="field required">
                  <label for="username">Username</label>
                  <input id="username" name="username" [(ngModel)]="draft.username" required />
                </div>
                <div class="field required">
                  <label for="fullName">Full name</label>
                  <input id="fullName" name="fullName" [(ngModel)]="draft.fullName" required />
                </div>
                <div class="field">
                  <label for="email">E-mail</label>
                  <input id="email" name="email" type="email" [(ngModel)]="draft.email" />
                </div>
                <div class="field required">
                  <label for="role">Role</label>
                  <select id="role" name="role" [(ngModel)]="draft.role" required>
                    @for (option of reference()?.roles ?? []; track option.value) {
                      <option [value]="option.value">{{ option.label }}</option>
                    }
                  </select>
                </div>
                <div class="field">
                  <label for="department">Department</label>
                  <input id="department" name="department" [(ngModel)]="draft.department" />
                </div>
                <div class="field required">
                  <label for="password">Password</label>
                  <input
                    id="password"
                    name="password"
                    type="password"
                    [(ngModel)]="draft.password"
                    required
                    minlength="8"
                  />
                  <span class="hint">At least 8 characters.</span>
                </div>
              </div>
              <div class="row end">
                <button class="btn primary" type="submit" [disabled]="busy() || !form.valid">
                  Create the account
                </button>
              </div>
            </form>
          </div>
        }

        <div class="table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th>Name</th>
                <th>Username</th>
                <th>Role</th>
                <th>Department</th>
                <th>E-mail</th>
                <th>Queues</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (user of users(); track user.id) {
                <tr>
                  <td class="strong">{{ user.fullName }}</td>
                  <td class="mono">{{ user.username }}</td>
                  <td>{{ user.roleLabel }}</td>
                  <td class="muted">{{ user.department || '—' }}</td>
                  <td class="muted">{{ user.email || '—' }}</td>
                  <td class="muted small">{{ user.candidateGroups.join(', ') || '—' }}</td>
                  <td class="nowrap">
                    <button class="btn ghost sm" (click)="toggleActive(user)" [disabled]="busy()">
                      {{ user.active ? 'Deactivate' : 'Activate' }}
                    </button>
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="7" class="muted">No user.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .card {
        margin-bottom: 16px;
      }
      dl {
        display: grid;
        grid-template-columns: minmax(150px, 26%) 1fr;
        gap: 8px 14px;
        margin: 0 0 18px;
        font-size: 13px;
      }
      dt {
        color: var(--ink-500);
      }
      dd {
        margin: 0;
      }
      code {
        background: rgba(0, 0, 0, 0.06);
        padding: 1px 5px;
        border-radius: 4px;
      }
      .new-user {
        background: var(--ink-050);
        border-bottom: 1px solid var(--ink-200);
      }
      .actions {
        margin-top: 4px;
      }
    `,
  ],
})
export class AdminComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);

  readonly engine = signal<EngineStatus | null>(null);
  readonly users = signal<UserSummary[]>([]);
  readonly reference = signal<ReferenceData | null>(null);
  readonly busy = signal(false);
  readonly creating = signal(false);

  draft = {
    username: '',
    fullName: '',
    email: '',
    role: 'FO',
    department: '',
    password: '',
  };

  ngOnInit(): void {
    this.api.referenceData().subscribe({ next: (data) => this.reference.set(data) });
    this.loadEngine();
    this.loadUsers();
  }

  loadEngine(): void {
    this.api.engineStatus().subscribe({
      next: (status) => this.engine.set(status),
      error: (error) => this.toasts.fromHttp(error, 'The engine status could not be read'),
    });
  }

  deploy(): void {
    this.busy.set(true);
    this.api.deployProcess().subscribe({
      next: (result) => {
        this.busy.set(false);
        this.toasts.success(`Deployed ${result.bpmnProcessId} version ${result.version}`);
        this.loadEngine();
      },
      error: (error) => {
        this.busy.set(false);
        this.toasts.fromHttp(error, 'The model could not be deployed');
      },
    });
  }

  synchronise(): void {
    this.busy.set(true);
    this.api.synchronise().subscribe({
      next: (result) => {
        this.busy.set(false);
        this.toasts.success(
          `${result.correctedClaims} complaint(s) realigned, ${result.breachedDeadlines} deadline(s) flagged`,
        );
      },
      error: (error) => {
        this.busy.set(false);
        this.toasts.fromHttp(error, 'The reconciliation failed');
      },
    });
  }

  createUser(form: NgForm): void {
    this.busy.set(true);
    this.api.createUser({ ...this.draft }).subscribe({
      next: (user) => {
        this.busy.set(false);
        this.creating.set(false);
        form.resetForm({ role: 'FO' });
        this.toasts.success(`${user.username} created`);
        this.loadUsers();
      },
      error: (error) => {
        this.busy.set(false);
        this.toasts.fromHttp(error, 'The account could not be created');
      },
    });
  }

  toggleActive(user: UserSummary): void {
    this.busy.set(true);
    this.api.updateUser(user.id, { active: !user.active }).subscribe({
      next: () => {
        this.busy.set(false);
        this.toasts.success(`${user.username} updated`);
        this.loadUsers();
      },
      error: (error) => {
        this.busy.set(false);
        this.toasts.fromHttp(error, 'The account could not be updated');
      },
    });
  }

  private loadUsers(): void {
    this.api.listUsers().subscribe({
      next: (users) => this.users.set(users),
      error: (error) => this.toasts.fromHttp(error, 'The user list could not be loaded'),
    });
  }
}
