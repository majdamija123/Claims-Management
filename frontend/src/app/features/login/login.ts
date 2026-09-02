import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { describeHttpError } from '../../core/toast.service';

/** Sign-in screen. */
@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <div class="login-page">
      <div class="panel">
        <div class="brand">
          <img class="brand-logo" src="cdg-logo.svg" alt="CDG" />
          <div>
            <h1>Claims Management</h1>
            <p class="muted small">Customer complaint handling — Caisse de Dépôt et de Gestion</p>
          </div>
        </div>

        <form (ngSubmit)="submit()" #form="ngForm">
          @if (error()) {
            <div class="banner error">{{ error() }}</div>
          }

          <div class="field required">
            <label for="username">Username</label>
            <input
              id="username"
              name="username"
              type="text"
              autocomplete="username"
              [(ngModel)]="username"
              required
              autofocus
            />
          </div>

          <div class="field required">
            <label for="password">Password</label>
            <input
              id="password"
              name="password"
              type="password"
              autocomplete="current-password"
              [(ngModel)]="password"
              required
            />
          </div>

          <button class="btn primary block" type="submit" [disabled]="busy() || !form.valid">
            @if (busy()) {
              <span class="spinner"></span>
            }
            Sign in
          </button>
        </form>

        <details class="accounts">
          <summary>Demo accounts</summary>
          <table class="data">
            <tbody>
              @for (account of demoAccounts; track account.username) {
                <tr class="clickable" (click)="fill(account.username)">
                  <td class="mono">{{ account.username }}</td>
                  <td class="muted">{{ account.role }}</td>
                </tr>
              }
            </tbody>
          </table>
          <p class="muted small">
            All demo accounts share the password <code>Cdg&#64;2026</code>. Click a row to fill it in.
          </p>
        </details>
      </div>

      <div class="aside">
        <h2>One process, five desks</h2>
        <p>
          Every complaint follows the BPMN process deployed on Camunda 8: qualification, then
          Front, Middle or Back Office, then validation before the customer is answered.
        </p>
        <ul>
          <li><strong>Qualification</strong> — admissibility, category and urgency</li>
          <li><strong>Front Office</strong> — answers directly, or escalates</li>
          <li><strong>Middle Office</strong> — investigates, or escalates further</li>
          <li><strong>Back Office</strong> — handles the remaining cases</li>
          <li><strong>Validation</strong> — approves the answer, or sends it back</li>
        </ul>
      </div>
    </div>
  `,
  styles: [
    `
      .login-page {
        min-height: 100vh;
        display: grid;
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      }
      .panel {
        display: flex;
        flex-direction: column;
        justify-content: center;
        padding: 40px min(9vw, 84px);
        background: var(--white);
      }
      .brand {
        display: flex;
        gap: 14px;
        align-items: center;
        margin-bottom: 32px;
      }
      .brand-logo {
        height: 38px;
        width: auto;
        flex: none;
      }
      h1 {
        font-size: 20px;
        margin-bottom: 2px;
      }
      form {
        max-width: 380px;
      }
      .accounts {
        margin-top: 30px;
        max-width: 380px;
        font-size: 13px;
      }
      .accounts summary {
        cursor: pointer;
        color: var(--ink-500);
        margin-bottom: 10px;
      }
      .accounts table {
        margin-bottom: 8px;
      }
      .accounts td {
        padding: 6px 8px;
      }
      code {
        background: var(--ink-100);
        padding: 1px 5px;
        border-radius: 4px;
      }
      .aside {
        background: linear-gradient(160deg, var(--cdg-green-800), var(--cdg-green-600));
        color: #e4efd2;
        padding: 48px min(6vw, 64px);
        display: flex;
        flex-direction: column;
        justify-content: center;
      }
      .aside h2 {
        color: #fff;
        font-size: 25px;
        margin-bottom: 14px;
      }
      .aside p {
        max-width: 46ch;
        line-height: 1.65;
      }
      .aside ul {
        margin-top: 20px;
        padding-left: 18px;
        line-height: 2;
        max-width: 46ch;
      }
      .aside strong {
        color: #ffffff;
      }
      @media (max-width: 900px) {
        .login-page {
          grid-template-columns: minmax(0, 1fr);
        }
        .aside {
          display: none;
        }
        .panel {
          padding: 32px 22px;
        }
      }
    `,
  ],
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  username = '';
  password = '';

  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly demoAccounts = [
    { username: 'qualif1', role: 'Qualification' },
    { username: 'fo1', role: 'Front Office' },
    { username: 'mo1', role: 'Middle Office' },
    { username: 'bo1', role: 'Back Office' },
    { username: 'valid1', role: 'Validation' },
    { username: 'supervisor', role: 'Supervisor' },
    { username: 'admin', role: 'Administrator' },
  ];

  fill(username: string): void {
    this.username = username;
    this.password = 'Cdg@2026';
  }

  submit(): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);

    this.auth.login(this.username.trim(), this.password).subscribe({
      next: () => {
        const redirect = this.route.snapshot.queryParamMap.get('redirect') ?? '/dashboard';
        void this.router.navigateByUrl(redirect);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(describeHttpError(err, 'Sign-in failed'));
      },
    });
  }
}
