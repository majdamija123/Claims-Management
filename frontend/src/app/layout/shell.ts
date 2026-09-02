import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ToastService } from '../core/toast.service';
import { TaskCounts } from '../core/models';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  badge?: 'tasks' | 'notifications';
  adminOnly?: boolean;
}

/** Application frame: navigation, header, toasts and the routed page. */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="shell">
      <aside class="sidebar" [class.open]="menuOpen()">
        <div class="brand">
          <img class="brand-logo" src="cdg-logo-white.svg" alt="CDG" />
          <div class="brand-text">
            <strong>Claims</strong>
            <span>Customer complaints</span>
          </div>
        </div>

        <nav>
          @for (item of visibleNav(); track item.path) {
            <a
              [routerLink]="item.path"
              routerLinkActive="active"
              (click)="menuOpen.set(false)"
              class="nav-item"
            >
              <span class="nav-icon">{{ item.icon }}</span>
              <span class="nav-label">{{ item.label }}</span>
              @if (item.badge === 'tasks' && counts(); as c) {
                @if (c.mine + c.available > 0) {
                  <span class="nav-badge">{{ c.mine + c.available }}</span>
                }
              }
              @if (item.badge === 'notifications' && api.unreadNotifications() > 0) {
                <span class="nav-badge alert">{{ api.unreadNotifications() }}</span>
              }
            </a>
          }
        </nav>

        <div class="sidebar-foot">
          <a routerLink="/claims/new" class="btn primary block" (click)="menuOpen.set(false)">
            + Register a complaint
          </a>
        </div>
      </aside>

      <div class="main">
        <header class="topbar">
          <button class="icon-btn menu-toggle" (click)="menuOpen.set(!menuOpen())" aria-label="Menu">
            ☰
          </button>

          <div class="topbar-title">{{ greeting() }}</div>
          <div class="spacer"></div>

          <a routerLink="/notifications" class="icon-btn" aria-label="Notifications">
            🔔
            @if (api.unreadNotifications() > 0) {
              <span class="dot-badge">{{ api.unreadNotifications() }}</span>
            }
          </a>

          <div class="user-menu">
            <button class="user-btn" (click)="userOpen.set(!userOpen())">
              <span class="avatar">{{ initials() }}</span>
              <span class="user-info">
                <span class="user-name">{{ auth.user()?.fullName }}</span>
                <span class="user-role">{{ auth.user()?.roleLabel }}</span>
              </span>
            </button>
            @if (userOpen()) {
              <div class="dropdown" (mouseleave)="userOpen.set(false)">
                <div class="dropdown-head">
                  <div class="strong">{{ auth.user()?.fullName }}</div>
                  <div class="muted small">{{ auth.user()?.email }}</div>
                  <div class="muted small">{{ auth.user()?.department }}</div>
                </div>
                <button class="dropdown-item" (click)="logout()">Sign out</button>
              </div>
            }
          </div>
        </header>

        <main>
          <router-outlet />
        </main>
      </div>

      @if (menuOpen()) {
        <div class="scrim" (click)="menuOpen.set(false)"></div>
      }

      <div class="toast-stack">
        @for (toast of toasts.toasts(); track toast.id) {
          <div class="toast {{ toast.kind }}" (click)="toasts.dismiss(toast.id)">
            {{ toast.message }}
          </div>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .shell {
        display: flex;
        min-height: 100vh;
      }
      .sidebar {
        width: var(--sidebar-width);
        flex: none;
        background: var(--cdg-green-700);
        color: #e4efd2;
        display: flex;
        flex-direction: column;
        position: sticky;
        top: 0;
        height: 100vh;
      }
      .brand {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 18px 18px 20px;
      }
      .brand-logo {
        height: 26px;
        width: auto;
        flex: none;
      }
      .brand-text {
        display: flex;
        flex-direction: column;
        line-height: 1.25;
        border-left: 1px solid rgba(255, 255, 255, 0.28);
        padding-left: 12px;
      }
      .brand-text strong {
        color: #fff;
        font-size: 15px;
      }
      .brand-text span {
        font-size: 11.5px;
        color: #cfe2ab;
      }
      nav {
        flex: 1;
        padding: 6px 10px;
        display: flex;
        flex-direction: column;
        gap: 2px;
        overflow-y: auto;
      }
      .nav-item {
        display: flex;
        align-items: center;
        gap: 11px;
        padding: 9px 12px;
        border-radius: var(--radius-sm);
        color: #e4efd2;
        font-size: 13.5px;
        font-weight: 500;
        text-decoration: none;
      }
      .nav-item:hover {
        background: rgba(255, 255, 255, 0.07);
        text-decoration: none;
      }
      .nav-item.active {
        background: rgba(255, 255, 255, 0.18);
        color: #fff;
      }
      .nav-icon {
        width: 18px;
        text-align: center;
        font-size: 14px;
      }
      .nav-label {
        flex: 1;
      }
      .nav-badge {
        background: rgba(255, 255, 255, 0.22);
        color: #fff;
        border-radius: 999px;
        padding: 1px 7px;
        font-size: 11px;
        font-weight: 600;
      }
      .nav-badge.alert {
        background: var(--red-600);
      }
      .sidebar-foot {
        padding: 12px;
        border-top: 1px solid rgba(255, 255, 255, 0.1);
      }
      .sidebar-foot .btn {
        background: var(--white);
        color: var(--cdg-green-700);
        font-weight: 600;
      }
      .sidebar-foot .btn:hover {
        background: #f2f7e8;
        text-decoration: none;
      }

      .main {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
      }
      .topbar {
        height: var(--topbar-height);
        background: var(--white);
        border-bottom: 1px solid var(--ink-200);
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 0 20px;
        position: sticky;
        top: 0;
        z-index: 20;
      }
      .topbar-title {
        font-weight: 600;
        font-size: 14.5px;
      }
      .spacer {
        flex: 1;
      }
      .icon-btn {
        position: relative;
        width: 36px;
        height: 36px;
        border: none;
        background: transparent;
        border-radius: var(--radius-sm);
        cursor: pointer;
        font-size: 16px;
        display: flex;
        align-items: center;
        justify-content: center;
        color: var(--ink-700);
        text-decoration: none;
      }
      .icon-btn:hover {
        background: var(--ink-100);
        text-decoration: none;
      }
      .dot-badge {
        position: absolute;
        top: 3px;
        right: 2px;
        background: var(--red-600);
        color: #fff;
        border-radius: 999px;
        font-size: 10px;
        font-weight: 700;
        min-width: 16px;
        height: 16px;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 0 4px;
      }
      .menu-toggle {
        display: none;
      }
      .user-menu {
        position: relative;
      }
      .user-btn {
        display: flex;
        align-items: center;
        gap: 9px;
        border: none;
        background: transparent;
        cursor: pointer;
        padding: 5px 8px;
        border-radius: var(--radius-sm);
        font: inherit;
      }
      .user-btn:hover {
        background: var(--ink-100);
      }
      .avatar {
        width: 32px;
        height: 32px;
        border-radius: 50%;
        background: var(--cdg-green-100);
        color: var(--cdg-green-700);
        font-weight: 600;
        font-size: 12px;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .user-info {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        line-height: 1.3;
      }
      .user-name {
        font-size: 13px;
        font-weight: 500;
      }
      .user-role {
        font-size: 11.5px;
        color: var(--ink-500);
      }
      .dropdown {
        position: absolute;
        right: 0;
        top: 46px;
        background: var(--white);
        border: 1px solid var(--ink-200);
        border-radius: var(--radius);
        box-shadow: var(--shadow-lg);
        min-width: 218px;
        overflow: hidden;
        z-index: 30;
      }
      .dropdown-head {
        padding: 12px 14px;
        border-bottom: 1px solid var(--ink-200);
      }
      .dropdown-item {
        width: 100%;
        text-align: left;
        border: none;
        background: none;
        padding: 11px 14px;
        font: inherit;
        font-size: 13px;
        cursor: pointer;
        color: var(--red-600);
      }
      .dropdown-item:hover {
        background: var(--ink-050);
      }
      main {
        flex: 1;
      }
      .scrim {
        display: none;
      }

      @media (max-width: 980px) {
        .sidebar {
          position: fixed;
          left: 0;
          top: 0;
          z-index: 60;
          transform: translateX(-100%);
          transition: transform 0.2s ease;
        }
        .sidebar.open {
          transform: translateX(0);
        }
        .menu-toggle {
          display: flex;
        }
        .scrim {
          display: block;
          position: fixed;
          inset: 0;
          background: rgba(16, 24, 40, 0.45);
          z-index: 50;
        }
        .user-info {
          display: none;
        }
      }
    `,
  ],
})
export class ShellComponent implements OnInit, OnDestroy {
  readonly auth = inject(AuthService);
  readonly api = inject(ApiService);
  readonly toasts = inject(ToastService);

  readonly menuOpen = signal(false);
  readonly userOpen = signal(false);
  readonly counts = signal<TaskCounts | null>(null);

  private timer?: ReturnType<typeof setInterval>;

  private readonly nav: NavItem[] = [
    { path: '/dashboard', label: 'Dashboard', icon: '▤' },
    { path: '/tasks', label: 'My tasks', icon: '✓', badge: 'tasks' },
    { path: '/claims', label: 'Complaints', icon: '☰' },
    { path: '/notifications', label: 'Notifications', icon: '🔔', badge: 'notifications' },
    { path: '/admin', label: 'Administration', icon: '⚙', adminOnly: true },
  ];

  ngOnInit(): void {
    this.refreshBadges();
    // The inbox and the bell are the only things that change without the user acting.
    this.timer = setInterval(() => this.refreshBadges(), 45_000);
  }

  ngOnDestroy(): void {
    if (this.timer) {
      clearInterval(this.timer);
    }
  }

  visibleNav(): NavItem[] {
    return this.nav.filter((item) => !item.adminOnly || this.auth.isAdmin());
  }

  greeting(): string {
    const hour = new Date().getHours();
    const part = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';
    const name = this.auth.user()?.fullName?.split(' ')[0] ?? '';
    return `${part}, ${name}`;
  }

  initials(): string {
    const name = this.auth.user()?.fullName ?? '';
    return name
      .split(' ')
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? '')
      .join('');
  }

  logout(): void {
    this.userOpen.set(false);
    this.auth.logout();
  }

  private refreshBadges(): void {
    this.api.taskCounts().subscribe({
      next: (counts) => this.counts.set(counts),
      error: () => this.counts.set(null),
    });
    this.api.refreshUnreadCount().subscribe({ error: () => undefined });
  }
}
