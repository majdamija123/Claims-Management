import { ChangeDetectionStrategy, Component, input } from '@angular/core';

type Tone = 'grey' | 'blue' | 'green' | 'amber' | 'red' | 'purple' | 'slate' | 'olive';

const STATUS_TONES: Record<string, Tone> = {
  IN_QUALIFICATION: 'slate',
  IN_FRONT_OFFICE: 'blue',
  IN_MIDDLE_OFFICE: 'purple',
  IN_BACK_OFFICE: 'olive',
  IN_VALIDATION: 'amber',
  RESOLVED: 'green',
  REJECTED: 'red',
  CANCELLED: 'grey',
};

const PRIORITY_TONES: Record<string, Tone> = {
  LOW: 'grey',
  NORMAL: 'blue',
  HIGH: 'amber',
  URGENT: 'red',
};

const STEP_TONES: Record<string, Tone> = {
  QUALIFICATION: 'slate',
  FRONT_OFFICE: 'blue',
  MIDDLE_OFFICE: 'purple',
  BACK_OFFICE: 'olive',
  VALIDATION: 'amber',
};

/** Coloured pill for a complaint status. */
@Component({
  selector: 'app-status-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="chip {{ tone() }}">{{ label() }}</span>`,
})
export class StatusChipComponent {
  readonly status = input.required<string>();
  readonly label = input.required<string>();

  tone(): Tone {
    return STATUS_TONES[this.status()] ?? 'grey';
  }
}

/** Coloured pill for the business priority. */
@Component({
  selector: 'app-priority-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="chip {{ tone() }}">{{ label() }}</span>`,
})
export class PriorityChipComponent {
  readonly priority = input.required<string>();
  readonly label = input.required<string>();

  tone(): Tone {
    return PRIORITY_TONES[this.priority()] ?? 'grey';
  }
}

/** Coloured pill for a workflow step. */
@Component({
  selector: 'app-step-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="chip plain {{ tone() }}">{{ label() }}</span>`,
})
export class StepChipComponent {
  readonly step = input<string | null | undefined>();
  readonly label = input<string | null | undefined>();

  tone(): Tone {
    return STEP_TONES[this.step() ?? ''] ?? 'grey';
  }
}

/** Deadline indicator: on track, close to the limit, or missed. */
@Component({
  selector: 'app-sla-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="chip {{ tone() }}">{{ text() }}</span>`,
})
export class SlaChipComponent {
  readonly health = input.required<string>();

  tone(): Tone {
    switch (this.health()) {
      case 'BREACHED':
        return 'red';
      case 'WARNING':
        return 'amber';
      default:
        return 'green';
    }
  }

  text(): string {
    switch (this.health()) {
      case 'BREACHED':
        return 'Overdue';
      case 'WARNING':
        return 'Due soon';
      default:
        return 'On track';
    }
  }
}

export const CHIPS = [
  StatusChipComponent,
  PriorityChipComponent,
  StepChipComponent,
  SlaChipComponent,
] as const;
