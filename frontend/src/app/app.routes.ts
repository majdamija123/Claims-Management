import { Routes } from '@angular/router';
import { adminGuard, anonymousGuard, authGuard } from './core/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    canActivate: [anonymousGuard],
    title: 'Sign in — CDG Claims',
    loadComponent: () => import('./features/login/login').then((m) => m.LoginComponent),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell').then((m) => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        title: 'Dashboard — CDG Claims',
        loadComponent: () =>
          import('./features/dashboard/dashboard').then((m) => m.DashboardComponent),
      },
      {
        path: 'tasks',
        title: 'My tasks — CDG Claims',
        loadComponent: () => import('./features/tasks/task-inbox').then((m) => m.TaskInboxComponent),
      },
      {
        path: 'claims',
        title: 'Complaints — CDG Claims',
        loadComponent: () =>
          import('./features/claims/claim-list').then((m) => m.ClaimListComponent),
      },
      {
        path: 'claims/new',
        title: 'Register a complaint — CDG Claims',
        loadComponent: () =>
          import('./features/claims/claim-create').then((m) => m.ClaimCreateComponent),
      },
      {
        path: 'claims/:id',
        title: 'Complaint — CDG Claims',
        loadComponent: () =>
          import('./features/claims/claim-detail').then((m) => m.ClaimDetailComponent),
      },
      {
        path: 'notifications',
        title: 'Notifications — CDG Claims',
        loadComponent: () =>
          import('./features/notifications/notifications').then((m) => m.NotificationsComponent),
      },
      {
        path: 'admin',
        canActivate: [adminGuard],
        title: 'Administration — CDG Claims',
        loadComponent: () => import('./features/admin/admin').then((m) => m.AdminComponent),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
