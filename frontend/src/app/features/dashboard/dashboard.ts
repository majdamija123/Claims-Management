import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DashboardStats } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { CHARTS } from '../../shared/charts';
import { DateTimePipe } from '../../shared/format';

/** Home screen: the figures a supervisor looks at first. */
@Component({
  selector: 'app-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, DateTimePipe, ...CHARTS],
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Dashboard</h1>
          <div class="subtitle">Complaint activity across the whole process</div>
        </div>
        <div class="row">
          <select [ngModel]="days()" (ngModelChange)="changeRange($event)" aria-label="Period">
            <option [ngValue]="7">Last 7 days</option>
            <option [ngValue]="30">Last 30 days</option>
            <option [ngValue]="90">Last 90 days</option>
          </select>
          <button class="btn secondary" (click)="load()" [disabled]="loading()">Refresh</button>
        </div>
      </div>

      @if (loading() && !stats()) {
        <div class="loading-block"><div class="spinner lg"></div></div>
      } @else if (stats(); as s) {
        <!-- Headline numbers: a hero figure reads faster than a chart of one value. -->
        <div class="grid cols-4 tiles">
          <div class="card tile">
            <div class="tile-label">Open complaints</div>
            <div class="tile-value">{{ s.open }}</div>
            <div class="tile-foot muted">{{ s.total }} registered in total</div>
          </div>
          <div class="card tile" [class.danger]="s.overdue > 0">
            <div class="tile-label">Past deadline</div>
            <div class="tile-value">{{ s.overdue }}</div>
            <div class="tile-foot muted">{{ s.slaComplianceRate }}% within SLA overall</div>
          </div>
          <div class="card tile">
            <div class="tile-label">Registered today</div>
            <div class="tile-value">{{ s.registeredToday }}</div>
            <div class="tile-foot muted">{{ s.closedToday }} closed today</div>
          </div>
          <div class="card tile">
            <div class="tile-label">Average handling time</div>
            <div class="tile-value">
              {{ s.averageResolutionHours !== null && s.averageResolutionHours !== undefined
                  ? s.averageResolutionHours + ' h'
                  : '—' }}
            </div>
            <div class="tile-foot muted">{{ s.resolved }} resolved · {{ s.rejected }} rejected</div>
          </div>
        </div>

        <div class="grid cols-2">
          <div class="card">
            <div class="card-head"><h2>Registrations and closures</h2></div>
            <div class="card-body"><app-trend-chart [points]="s.trend" /></div>
          </div>

          <div class="card">
            <div class="card-head">
              <h2>Open tasks per step</h2>
              <a routerLink="/tasks" class="small">Open the inbox →</a>
            </div>
            <div class="card-body"><app-bar-chart [data]="s.workload" /></div>
          </div>

          <div class="card">
            <div class="card-head"><h2>Complaints by category</h2></div>
            <div class="card-body"><app-bar-chart [data]="s.byType" /></div>
          </div>

          <div class="card">
            <div class="card-head"><h2>How complaints arrive</h2></div>
            <div class="card-body"><app-donut-chart [data]="s.byChannel" /></div>
          </div>

          <div class="card">
            <div class="card-head"><h2>Current status</h2></div>
            <div class="card-body"><app-bar-chart [data]="s.byStatus" /></div>
          </div>

          <div class="card">
            <div class="card-head"><h2>Open complaints by urgency</h2></div>
            <div class="card-body"><app-bar-chart [data]="s.byPriority" /></div>
          </div>
        </div>

        <div class="card attention">
          <div class="card-head">
            <h2>Needs attention</h2>
            <a routerLink="/claims" [queryParams]="{ overdue: true }" class="small">
              See all overdue →
            </a>
          </div>
          @if (s.attention.length === 0) {
            <div class="empty">
              <div class="icon">✓</div>
              Nothing is past its deadline. Every open complaint is on track.
            </div>
          } @else {
            <div class="table-wrap">
              <table class="data">
                <thead>
                  <tr>
                    <th>Reference</th>
                    <th>Subject</th>
                    <th>Customer</th>
                    <th>Step</th>
                    <th>Urgency</th>
                    <th>Deadline</th>
                    <th class="num">Late by</th>
                  </tr>
                </thead>
                <tbody>
                  @for (claim of s.attention; track claim.id) {
                    <tr class="clickable" [routerLink]="['/claims', claim.id]">
                      <td class="mono">{{ claim.reference }}</td>
                      <td class="truncate">{{ claim.subject }}</td>
                      <td>{{ claim.customerName }}</td>
                      <td>{{ claim.step }}</td>
                      <td>{{ claim.priority }}</td>
                      <td class="nowrap">{{ claim.dueAt | dateTime }}</td>
                      <td class="num late">{{ claim.hoursLate }} h</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
      } @else {
        <div class="banner error">The dashboard could not be loaded.</div>
      }
    </div>
  `,
  styles: [
    `
      .tiles {
        margin-bottom: 16px;
      }
      .tile {
        padding: 16px 18px;
      }
      .tile-label {
        font-size: 12px;
        font-weight: 500;
        color: var(--ink-500);
        text-transform: uppercase;
        letter-spacing: 0.04em;
      }
      .tile-value {
        font-size: 30px;
        font-weight: 700;
        line-height: 1.25;
        margin: 6px 0 2px;
        font-variant-numeric: tabular-nums;
      }
      .tile.danger .tile-value {
        color: var(--red-600);
      }
      .tile-foot {
        font-size: 12px;
      }
      .attention {
        margin-top: 16px;
      }
      .late {
        color: var(--red-600);
        font-weight: 600;
      }
      select {
        width: auto;
        min-width: 150px;
      }
    `,
  ],
})
export class DashboardComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  readonly auth = inject(AuthService);

  readonly stats = signal<DashboardStats | null>(null);
  readonly loading = signal(false);
  readonly days = signal(30);

  ngOnInit(): void {
    this.load();
  }

  changeRange(days: number): void {
    this.days.set(days);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.dashboard(this.days()).subscribe({
      next: (stats) => {
        this.stats.set(stats);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.toasts.fromHttp(error, 'The dashboard could not be loaded');
      },
    });
  }
}
