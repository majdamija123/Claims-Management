/** Shapes returned by the Spring Boot API. Kept in step with ma.cdg.claims.web.dto. */

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Option {
  value: string;
  label: string;
}

export interface StepOption extends Option {
  candidateGroup: string;
  role: string;
  order: number;
  decisions: Option[];
}

export interface ReferenceData {
  claimTypes: Option[];
  channels: Option[];
  priorities: Option[];
  statuses: Option[];
  steps: StepOption[];
  roles: Option[];
}

export interface UserSummary {
  id: number;
  username: string;
  fullName: string;
  email?: string;
  role: string;
  roleLabel: string;
  department?: string;
  active: boolean;
  candidateGroups: string[];
  workflowStep?: string;
}

export interface LoginResponse {
  token: string;
  expiresInSeconds: number;
  user: UserSummary;
}

export interface ClaimSummary {
  id: number;
  reference: string;
  customerName: string;
  subject: string;
  type: string;
  typeLabel: string;
  priority: string;
  priorityLabel: string;
  status: string;
  statusLabel: string;
  currentStep?: string;
  currentStepLabel?: string;
  currentAssignee?: string;
  channel: string;
  channelLabel: string;
  createdAt: string;
  slaDueAt?: string;
  overdue: boolean;
  slaHealth: 'OK' | 'WARNING' | 'BREACHED';
}

export interface ClaimEvent {
  id: number;
  type: string;
  typeLabel: string;
  step?: string;
  stepLabel?: string;
  decision?: string;
  decisionLabel?: string;
  actor: string;
  actorRole?: string;
  comment?: string;
  occurredAt: string;
}

export interface ClaimDetail {
  summary: ClaimSummary;
  customerEmail?: string;
  customerPhone?: string;
  customerReference?: string;
  entity?: string;
  description: string;
  resolution?: string;
  rejectionReason?: string;
  predictedType?: string;
  predictedTypeLabel?: string;
  predictionConfidence?: number;
  processInstanceKey?: number;
  processVersion?: number;
  returnCount: number;
  slaBreached: boolean;
  stepStartedAt?: string;
  updatedAt: string;
  closedAt?: string;
  createdBy?: string;
  history: ClaimEvent[];
  openTasks: TaskSummary[];
  processVariables: Record<string, unknown>;
}

export interface CreateClaimRequest {
  customerName: string;
  customerEmail?: string;
  customerPhone?: string;
  customerReference?: string;
  channel: string;
  entity?: string;
  subject: string;
  description: string;
  type?: string;
  priority: string;
}

export interface TypeSuggestion {
  type: string;
  typeLabel: string;
  confidence: number;
  source: 'MODEL' | 'RULES';
  alternatives: { type: string; typeLabel: string; confidence: number }[];
}

export interface TaskSummary {
  taskKey: string;
  elementId: string;
  name: string;
  step?: string;
  stepLabel: string;
  state: string;
  assignee?: string;
  candidateGroups: string[];
  createdAt: string;
  dueDate?: string;
  overdue: boolean;
  priority?: number;
  processInstanceKey: string;
  canAct: boolean;
  decisions: Option[];
  claim?: ClaimSummary;
}

export type InboxScope = 'MINE' | 'AVAILABLE' | 'GROUP' | 'COMPLETED';

export interface TaskCounts {
  mine: number;
  available: number;
  group: number;
}

export interface CompleteTaskRequest {
  decision: string;
  comment?: string;
  resolution?: string;
  rejectionReason?: string;
  type?: string;
  priority?: string;
}

export interface Slice {
  key: string;
  label: string;
  count: number;
}

export interface TrendPoint {
  date: string;
  created: number;
  closed: number;
}

export interface OverdueClaim {
  id: number;
  reference: string;
  subject: string;
  customerName: string;
  step: string;
  priority: string;
  dueAt: string;
  hoursLate: number;
}

export interface DashboardStats {
  total: number;
  open: number;
  resolved: number;
  rejected: number;
  cancelled: number;
  overdue: number;
  registeredToday: number;
  closedToday: number;
  averageResolutionHours?: number;
  slaComplianceRate: number;
  byStatus: Slice[];
  byType: Slice[];
  byChannel: Slice[];
  byPriority: Slice[];
  byStep: Slice[];
  workload: Slice[];
  trend: TrendPoint[];
  attention: OverdueClaim[];
}

export interface AppNotification {
  id: number;
  title: string;
  message: string;
  level: 'INFO' | 'SUCCESS' | 'WARNING' | 'DANGER';
  claimId?: number;
  claimReference?: string;
  read: boolean;
  createdAt: string;
}

export interface EngineStatus {
  simulated: boolean;
  description: string;
  processId: string;
  deployOnStartup: boolean;
  lastDeployment?: string;
}

export interface ClaimFilters {
  search?: string;
  status?: string[];
  type?: string[];
  priority?: string[];
  channel?: string[];
  step?: string;
  assignee?: string;
  overdue?: boolean;
  openOnly?: boolean;
  page?: number;
  size?: number;
  sort?: string;
  direction?: 'asc' | 'desc';
}

/** RFC 9457 problem document returned by the backend on failure. */
export interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
  fieldErrors?: Record<string, string>;
}
