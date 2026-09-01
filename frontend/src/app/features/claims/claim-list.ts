import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { ClaimFilters, ClaimSummary, PageResponse, ReferenceData } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { CHIPS } from '../../shared/chips';
import { DateTimePipe, RelativeTimePipe } from '../../shared/format';

/** The complaint register: search, filter, sort, open and export. */
@Component({
  selector: 'app-claim-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, DateTimePipe, RelativeTimePipe, ...CHIPS],
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Complaints</h1>
          <div class="subtitle">
            @if (page(); as result) {
              {{ result.totalElements }} complaint{{ result.totalElements === 1 ? '' : 's' }} match
              the current filters
            }
          </div>
        </div>
        <div class="row">
          <button class="btn secondary" (click)="download('xlsx')" [disabled]="exporting()">
            Export Excel
          </button>
          <button class="btn secondary" (click)="download('csv')" [disabled]="exporting()">
            Export CSV
          </button>
          <a routerLink="/claims/new" class="btn primary">+ Register</a>
        </div>
      </div>

      <div class="card filters">
        <div class="filter-grid">
          <div class="field">
            <label for="search">Search</label>
            <input
              id="search"
              type="search"
              placeholder="Reference, subject, customer…"
              [(ngModel)]="filters.search"
              (keyup.enter)="apply()"
            />
          </div>
          <div class="field">
            <label for="status">Status</label>
            <select id="status" [(ngModel)]="status" (change)="apply()">
              <option value="">All statuses</option>
              @for (option of reference()?.statuses ?? []; track option.value) {
                <option [value]="option.value">{{ option.label }}</option>
              }
            </select>
          </div>
          <div class="field">
            <label for="type">Category</label>
            <select id="type" [(ngModel)]="type" (change)="apply()">
              <option value="">All categories</option>
              @for (option of reference()?.claimTypes ?? []; track option.value) {
                <option [value]="option.value">{{ option.label }}</option>
              }
            </select>
          </div>
          <div class="field">
            <label for="priority">Urgency</label>
            <select id="priority" [(ngModel)]="priority" (change)="apply()">
              <option value="">Any urgency</option>
              @for (option of reference()?.priorities ?? []; track option.value) {
                <option [value]="option.value">{{ option.label }}</option>
              }
            </select>
          </div>
          <div class="field">
            <label for="step">Current step</label>
            <select id="step" [(ngModel)]="step" (change)="apply()">
              <option value="">Any step</option>
              @for (option of reference()?.steps ?? []; track option.value) {
                <option [value]="option.value">{{ option.label }}</option>
              }
            </select>
          </div>
          <div class="field toggles">
            <label>Quick filters</label>
            <div class="row">
              <label class="check">
                <input type="checkbox" [(ngModel)]="openOnly" (change)="apply()" /> Open only
              </label>
              <label class="check">
                <input type="checkbox" [(ngModel)]="overdue" (change)="apply()" /> Overdue
              </label>
            </div>
          </div>
        </div>
        <div class="row end">
          <button class="btn ghost sm" (click)="reset()">Clear filters</button>
          <button class="btn secondary sm" (click)="apply()">Apply</button>
        </div>
      </div>

      <div class="card">
        @if (loading()) {
          <div class="loading-block"><div class="spinner lg"></div></div>
        } @else if (page(); as result) {
          @if (result.content.length === 0) {
            <div class="empty">
              <div class="icon">☰</div>
              No complaint matches these filters.
            </div>
          } @else {
            <div class="table-wrap">
              <table class="data">
                <thead>
                  <tr>
                    <th class="sortable" (click)="sortBy('reference')">Reference {{ arrow('reference') }}</th>
                    <th class="sortable" (click)="sortBy('customerName')">Customer {{ arrow('customerName') }}</th>
                    <th>Subject</th>
                    <th>Category</th>
                    <th class="sortable" (click)="sortBy('priority')">Urgency {{ arrow('priority') }}</th>
                    <th>Status</th>
                    <th>Step</th>
                    <th>Assignee</th>
                    <th class="sortable" (click)="sortBy('slaDueAt')">Deadline {{ arrow('slaDueAt') }}</th>
                    <th class="sortable" (click)="sortBy('createdAt')">Registered {{ arrow('createdAt') }}</th>
                  </tr>
                </thead>
                <tbody>
                  @for (claim of result.content; track claim.id) {
                    <tr class="clickable" [routerLink]="['/claims', claim.id]">
                      <td class="mono strong">{{ claim.reference }}</td>
                      <td class="nowrap">{{ claim.customerName }}</td>
                      <td class="truncate">{{ claim.subject }}</td>
                      <td class="nowrap">{{ claim.typeLabel }}</td>
                      <td><app-priority-chip [priority]="claim.priority" [label]="claim.priorityLabel" /></td>
                      <td><app-status-chip [status]="claim.status" [label]="claim.statusLabel" /></td>
                      <td class="nowrap">
                        <app-step-chip [step]="claim.currentStep" [label]="claim.currentStepLabel ?? '—'" />
                      </td>
                      <td class="nowrap muted">{{ claim.currentAssignee ?? '—' }}</td>
                      <td class="nowrap">
                        @if (claim.slaDueAt) {
                          <span [class.late]="claim.overdue">{{ claim.slaDueAt | relativeTime }}</span>
                        } @else {
                          <span class="muted">—</span>
                        }
                      </td>
                      <td class="nowrap muted">{{ claim.createdAt | dateTime }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>

            <div class="row pager">
              <span class="small muted">
                Showing {{ result.content.length }} of {{ result.totalElements }}
              </span>
              <div class="spacer"></div>
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
      </div>
    </div>
  `,
  styles: [
    `
      .filters {
        padding: 16px 18px 12px;
        margin-bottom: 16px;
      }
      .filter-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
        gap: 0 14px;
      }
      .check {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        font-weight: 400;
        color: var(--ink-700);
        cursor: pointer;
      }
      .check input {
        width: auto;
        min-height: auto;
      }
      .toggles .row {
        min-height: 38px;
      }
      .late {
        color: var(--red-600);
        font-weight: 600;
      }
      .pager {
        padding: 12px 16px;
        align-items: center;
        border-top: 1px solid var(--ink-100);
      }
    `,
  ],
})
export class ClaimListComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly route = inject(ActivatedRoute);

  readonly page = signal<PageResponse<ClaimSummary> | null>(null);
  readonly reference = signal<ReferenceData | null>(null);
  readonly loading = signal(false);
  readonly exporting = signal(false);

  filters: ClaimFilters = { page: 0, size: 20, sort: 'createdAt', direction: 'desc' };
  status = '';
  type = '';
  priority = '';
  step = '';
  openOnly = false;
  overdue = false;

  ngOnInit(): void {
    this.api.referenceData().subscribe({ next: (data) => this.reference.set(data) });

    // The dashboard links here with ?overdue=true.
    if (this.route.snapshot.queryParamMap.get('overdue') === 'true') {
      this.overdue = true;
    }
    const step = this.route.snapshot.queryParamMap.get('step');
    if (step) {
      this.step = step;
    }
    this.load();
  }

  apply(): void {
    this.filters.page = 0;
    this.load();
  }

  reset(): void {
    this.filters = { page: 0, size: 20, sort: 'createdAt', direction: 'desc' };
    this.status = '';
    this.type = '';
    this.priority = '';
    this.step = '';
    this.openOnly = false;
    this.overdue = false;
    this.load();
  }

  go(page: number): void {
    this.filters.page = page;
    this.load();
  }

  sortBy(column: string): void {
    if (this.filters.sort === column) {
      this.filters.direction = this.filters.direction === 'asc' ? 'desc' : 'asc';
    } else {
      this.filters.sort = column;
      this.filters.direction = 'asc';
    }
    this.load();
  }

  arrow(column: string): string {
    if (this.filters.sort !== column) {
      return '';
    }
    return this.filters.direction === 'asc' ? '↑' : '↓';
  }

  download(format: 'xlsx' | 'csv'): void {
    this.exporting.set(true);
    const url = this.api.exportUrl(format, this.currentFilters());
    const stamp = new Date().toISOString().slice(0, 10);
    this.api.download(url, `complaints-${stamp}.${format}`).subscribe({
      next: () => {
        this.exporting.set(false);
        this.toasts.success('Export downloaded');
      },
      error: (error) => {
        this.exporting.set(false);
        this.toasts.fromHttp(error, 'The export could not be generated');
      },
    });
  }

  private currentFilters(): ClaimFilters {
    return {
      ...this.filters,
      status: this.status ? [this.status] : undefined,
      type: this.type ? [this.type] : undefined,
      priority: this.priority ? [this.priority] : undefined,
      step: this.step || undefined,
      openOnly: this.openOnly || undefined,
      overdue: this.overdue || undefined,
    };
  }

  private load(): void {
    this.loading.set(true);
    this.api.listClaims(this.currentFilters()).subscribe({
      next: (result) => {
        this.page.set(result);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.toasts.fromHttp(error, 'The complaint list could not be loaded');
      },
    });
  }
}
