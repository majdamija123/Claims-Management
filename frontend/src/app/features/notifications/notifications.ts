import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AppNotification } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { DateTimePipe, RelativeTimePipe } from '../../shared/format';

/** What the application wants this user to know about. */
@Component({
  selector: 'app-notifications',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, DateTimePipe, RelativeTimePipe],
  template: `
    <div class="page narrow">
      <div class="page-head">
        <div>
          <h1>Notifications</h1>
          <div class="subtitle">New work, missed deadlines and closures that concern you</div>
        </div>
        <button class="btn secondary" (click)="markAll()" [disabled]="busy() || unread() === 0">
          Mark all as read
        </button>
      </div>

      <div class="card">
        @if (loading()) {
          <div class="loading-block"><div class="spinner lg"></div></div>
        } @else if (items().length === 0) {
          <div class="empty">
            <div class="icon">🔔</div>
            Nothing to report.
          </div>
        } @else {
          <ul class="list">
            @for (item of items(); track item.id) {
              <li [class.unread]="!item.read" (click)="open(item)">
                <span class="marker {{ tone(item.level) }}"></span>
                <div class="body">
                  <div class="row title-row">
                    <strong>{{ item.title }}</strong>
                    <span class="spacer"></span>
                    <span class="muted small nowrap" [title]="item.createdAt | dateTime">
                      {{ item.createdAt | relativeTime }}
                    </span>
                  </div>
                  <div class="small">{{ item.message }}</div>
                  @if (item.claimId) {
                    <a [routerLink]="['/claims', item.claimId]" class="small">
                      Open {{ item.claimReference }} →
                    </a>
                  }
                </div>
              </li>
            }
          </ul>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .narrow {
        max-width: 860px;
      }
      .list {
        list-style: none;
        margin: 0;
        padding: 0;
      }
      .list li {
        display: flex;
        gap: 12px;
        padding: 14px 18px;
        border-bottom: 1px solid var(--ink-100);
        cursor: pointer;
      }
      .list li:last-child {
        border-bottom: none;
      }
      .list li:hover {
        background: var(--ink-050);
      }
      .list li.unread {
        background: var(--navy-050);
      }
      .list li.unread:hover {
        background: var(--navy-100);
      }
      .marker {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        margin-top: 6px;
        flex: none;
        background: var(--navy-600);
      }
      .marker.green {
        background: var(--green-600);
      }
      .marker.amber {
        background: var(--amber-600);
      }
      .marker.red {
        background: var(--red-600);
      }
      .body {
        flex: 1;
        min-width: 0;
      }
      .title-row {
        margin-bottom: 2px;
      }
    `,
  ],
})
export class NotificationsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);

  readonly items = signal<AppNotification[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);

  ngOnInit(): void {
    this.load();
  }

  unread(): number {
    return this.items().filter((item) => !item.read).length;
  }

  tone(level: AppNotification['level']): string {
    switch (level) {
      case 'SUCCESS':
        return 'green';
      case 'WARNING':
        return 'amber';
      case 'DANGER':
        return 'red';
      default:
        return '';
    }
  }

  open(item: AppNotification): void {
    if (item.read) {
      return;
    }
    this.api.markNotificationRead(item.id).subscribe({
      next: () => {
        this.items.update((list) =>
          list.map((entry) => (entry.id === item.id ? { ...entry, read: true } : entry)),
        );
        this.api.refreshUnreadCount().subscribe();
      },
      error: () => undefined,
    });
  }

  markAll(): void {
    this.busy.set(true);
    this.api.markAllNotificationsRead().subscribe({
      next: () => {
        this.busy.set(false);
        this.items.update((list) => list.map((entry) => ({ ...entry, read: true })));
        this.api.refreshUnreadCount().subscribe();
      },
      error: (error) => {
        this.busy.set(false);
        this.toasts.fromHttp(error, 'The notifications could not be updated');
      },
    });
  }

  private load(): void {
    this.loading.set(true);
    this.api.notifications(50).subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.toasts.fromHttp(error, 'The notifications could not be loaded');
      },
    });
  }
}
