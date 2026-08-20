import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { Slice, TrendPoint } from '../core/models';

/**
 * Categorical palette, validated for colour-vision deficiency against a white chart
 * surface. Hues are assigned by fixed slot order and never cycled — a seventh category
 * folds into "Other" rather than repeating a colour.
 */
export const CATEGORICAL = [
  '#2a78d6',
  '#eb6834',
  '#1baf7a',
  '#eda100',
  '#e87ba4',
  '#4a3aa7',
] as const;

/** Single-series marks use one brand hue; length carries the magnitude, not colour. */
export const SINGLE_SERIES = '#1e56a0';

/** Horizontal bars for one measure across named categories. */
@Component({
  selector: 'app-bar-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (rows().length === 0) {
      <div class="empty small">No data for this period</div>
    } @else {
      <div class="bars" role="table">
        @for (row of rows(); track row.key) {
          <div class="bar-row" role="row">
            <div class="bar-label" role="cell" [title]="row.label">{{ row.label }}</div>
            <div class="bar-track" role="cell">
              <div
                class="bar-fill"
                [style.width.%]="row.width"
                [style.background]="color"
                [attr.aria-label]="row.label + ': ' + row.count"
              ></div>
            </div>
            <div class="bar-value" role="cell">{{ row.count }}</div>
          </div>
        }
      </div>
    }
  `,
  styles: [
    `
      .bars {
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
      .bar-row {
        display: grid;
        grid-template-columns: minmax(96px, 34%) 1fr 38px;
        align-items: center;
        gap: 10px;
      }
      .bar-label {
        font-size: 12.5px;
        color: var(--ink-700);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .bar-track {
        height: 10px;
        background: var(--ink-100);
        border-radius: 999px;
        overflow: hidden;
      }
      /* Rounded at the data end only; the baseline end stays square. */
      .bar-fill {
        height: 100%;
        min-width: 3px;
        border-radius: 0 4px 4px 0;
        transition: width 0.35s ease;
      }
      .bar-value {
        font-size: 12.5px;
        font-weight: 600;
        text-align: right;
        font-variant-numeric: tabular-nums;
        color: var(--ink-900);
      }
    `,
  ],
})
export class BarChartComponent {
  readonly data = input.required<Slice[]>();
  readonly color = SINGLE_SERIES;

  readonly rows = computed(() => {
    const slices = this.data() ?? [];
    const max = Math.max(1, ...slices.map((slice) => slice.count));
    return slices.map((slice) => ({
      key: slice.key,
      label: slice.label,
      count: slice.count,
      width: (slice.count / max) * 100,
    }));
  });
}

/** Part-to-whole for a handful of categories, with a legend carrying the counts. */
@Component({
  selector: 'app-donut-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (total() === 0) {
      <div class="empty small">No data for this period</div>
    } @else {
      <div class="donut-wrap">
        <svg viewBox="0 0 120 120" class="donut" role="img" [attr.aria-label]="ariaLabel()">
          @for (arc of arcs(); track arc.key) {
            <circle
              cx="60"
              cy="60"
              r="46"
              fill="none"
              [attr.stroke]="arc.color"
              stroke-width="16"
              [attr.stroke-dasharray]="arc.dash"
              [attr.stroke-dashoffset]="arc.offset"
              transform="rotate(-90 60 60)"
            >
              <title>{{ arc.label }}: {{ arc.count }}</title>
            </circle>
          }
          <text x="60" y="56" text-anchor="middle" class="donut-total">{{ total() }}</text>
          <text x="60" y="70" text-anchor="middle" class="donut-caption">total</text>
        </svg>
        <ul class="legend">
          @for (arc of arcs(); track arc.key) {
            <li>
              <span class="swatch" [style.background]="arc.color"></span>
              <span class="legend-label">{{ arc.label }}</span>
              <span class="legend-value">{{ arc.count }}</span>
            </li>
          }
        </ul>
      </div>
    }
  `,
  styles: [
    `
      .donut-wrap {
        display: flex;
        gap: 20px;
        align-items: center;
        flex-wrap: wrap;
      }
      .donut {
        width: 152px;
        height: 152px;
        flex: none;
      }
      .donut-total {
        font-size: 19px;
        font-weight: 700;
        fill: var(--ink-900);
      }
      .donut-caption {
        font-size: 8px;
        fill: var(--ink-500);
        text-transform: uppercase;
        letter-spacing: 0.08em;
      }
      .legend {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 7px;
        min-width: 168px;
        flex: 1;
      }
      .legend li {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 12.5px;
      }
      .swatch {
        width: 9px;
        height: 9px;
        border-radius: 2px;
        flex: none;
      }
      .legend-label {
        color: var(--ink-700);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .legend-value {
        margin-left: auto;
        font-weight: 600;
        font-variant-numeric: tabular-nums;
      }
    `,
  ],
})
export class DonutChartComponent {
  readonly data = input.required<Slice[]>();

  private readonly circumference = 2 * Math.PI * 46;

  readonly total = computed(() =>
    (this.data() ?? []).reduce((sum, slice) => sum + slice.count, 0),
  );

  readonly ariaLabel = computed(() =>
    (this.data() ?? []).map((slice) => `${slice.label}: ${slice.count}`).join(', '),
  );

  readonly arcs = computed(() => {
    const slices = (this.data() ?? []).filter((slice) => slice.count > 0);
    const total = this.total() || 1;
    // A 2px gap of surface colour separates neighbouring segments.
    const gap = 2;
    let consumed = 0;

    return slices.map((slice, index) => {
      const length = Math.max((slice.count / total) * this.circumference - gap, 1);
      const arc = {
        key: slice.key,
        label: slice.label,
        count: slice.count,
        color: CATEGORICAL[index % CATEGORICAL.length],
        dash: `${length} ${this.circumference - length}`,
        offset: -consumed,
      };
      consumed += (slice.count / total) * this.circumference;
      return arc;
    });
  });
}

/** Registrations against closures over time, with a hover crosshair. */
@Component({
  selector: 'app-trend-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (points().length === 0) {
      <div class="empty small">No activity recorded yet</div>
    } @else {
      <div class="trend">
        <div class="legend-row">
          <span class="legend-item">
            <span class="swatch" [style.background]="createdColor"></span>Registered
          </span>
          <span class="legend-item">
            <span class="swatch" [style.background]="closedColor"></span>Closed
          </span>
        </div>

        <svg
          class="plot"
          viewBox="0 0 600 190"
          preserveAspectRatio="none"
          role="img"
          [attr.aria-label]="ariaLabel()"
          (mousemove)="onMove($event)"
          (mouseleave)="hover.set(null)"
        >
          <!-- Recessive gridlines -->
          @for (line of gridLines(); track line.y) {
            <line x1="0" [attr.y1]="line.y" x2="600" [attr.y2]="line.y" class="grid" />
          }

          <path [attr.d]="createdArea()" [attr.fill]="createdColor" opacity="0.09" />
          <path [attr.d]="createdLine()" fill="none" [attr.stroke]="createdColor" stroke-width="2" />
          <path [attr.d]="closedLine()" fill="none" [attr.stroke]="closedColor" stroke-width="2" />

          @if (hover(); as point) {
            <line [attr.x1]="point.x" y1="0" [attr.x2]="point.x" y2="190" class="crosshair" />
            <circle [attr.cx]="point.x" [attr.cy]="point.createdY" r="4.5" [attr.fill]="createdColor"
                    stroke="#fff" stroke-width="2" />
            <circle [attr.cx]="point.x" [attr.cy]="point.closedY" r="4.5" [attr.fill]="closedColor"
                    stroke="#fff" stroke-width="2" />
          }
        </svg>

        <div class="axis">
          <span>{{ firstLabel() }}</span>
          <span class="spacer"></span>
          <span>{{ lastLabel() }}</span>
        </div>

        @if (hover(); as point) {
          <div class="tooltip" [style.left.%]="point.percent">
            <div class="tooltip-date">{{ point.date }}</div>
            <div><span class="dot" [style.background]="createdColor"></span>{{ point.created }} registered</div>
            <div><span class="dot" [style.background]="closedColor"></span>{{ point.closed }} closed</div>
          </div>
        }
      </div>
    }
  `,
  styles: [
    `
      .trend {
        position: relative;
      }
      .legend-row {
        display: flex;
        gap: 16px;
        margin-bottom: 10px;
        font-size: 12.5px;
        color: var(--ink-700);
      }
      .legend-item {
        display: flex;
        align-items: center;
        gap: 6px;
      }
      .swatch {
        width: 9px;
        height: 9px;
        border-radius: 2px;
      }
      .plot {
        width: 100%;
        height: 190px;
        overflow: visible;
      }
      .grid {
        stroke: var(--ink-200);
        stroke-width: 1;
      }
      .crosshair {
        stroke: var(--ink-400);
        stroke-width: 1;
        stroke-dasharray: 3 3;
      }
      .axis {
        display: flex;
        margin-top: 6px;
        font-size: 11.5px;
        color: var(--ink-500);
      }
      .spacer {
        flex: 1;
      }
      .tooltip {
        position: absolute;
        top: 4px;
        transform: translateX(-50%);
        background: var(--white);
        border: 1px solid var(--ink-200);
        box-shadow: var(--shadow-md);
        border-radius: var(--radius-sm);
        padding: 8px 10px;
        font-size: 12px;
        pointer-events: none;
        white-space: nowrap;
      }
      .tooltip-date {
        font-weight: 600;
        margin-bottom: 4px;
      }
      .dot {
        display: inline-block;
        width: 7px;
        height: 7px;
        border-radius: 50%;
        margin-right: 6px;
      }
    `,
  ],
})
export class TrendChartComponent {
  readonly points = input.required<TrendPoint[]>();

  readonly createdColor = CATEGORICAL[0];
  readonly closedColor = CATEGORICAL[2];

  private readonly width = 600;
  private readonly height = 190;

  readonly hover = signal<{
    x: number;
    percent: number;
    createdY: number;
    closedY: number;
    created: number;
    closed: number;
    date: string;
  } | null>(null);

  private readonly max = computed(() =>
    Math.max(1, ...this.points().flatMap((point) => [point.created, point.closed])),
  );

  readonly gridLines = computed(() =>
    [0, 0.25, 0.5, 0.75, 1].map((fraction) => ({ y: this.height * fraction })),
  );

  readonly ariaLabel = computed(() => {
    const total = this.points().reduce((sum, point) => sum + point.created, 0);
    return `Registrations and closures over ${this.points().length} days, ${total} registered in total`;
  });

  readonly firstLabel = computed(() => formatDay(this.points()[0]?.date));
  readonly lastLabel = computed(() => formatDay(this.points().at(-1)?.date));

  readonly createdLine = computed(() => this.line((point) => point.created));
  readonly closedLine = computed(() => this.line((point) => point.closed));

  readonly createdArea = computed(() => {
    const line = this.createdLine();
    if (!line) {
      return '';
    }
    return `${line} L ${this.width} ${this.height} L 0 ${this.height} Z`;
  });

  onMove(event: MouseEvent): void {
    const points = this.points();
    if (points.length === 0) {
      return;
    }
    const target = event.currentTarget as SVGSVGElement;
    const bounds = target.getBoundingClientRect();
    const ratio = Math.min(Math.max((event.clientX - bounds.left) / bounds.width, 0), 1);
    const index = Math.round(ratio * (points.length - 1));
    const point = points[index];

    this.hover.set({
      x: this.xOf(index),
      percent: (index / Math.max(1, points.length - 1)) * 100,
      createdY: this.yOf(point.created),
      closedY: this.yOf(point.closed),
      created: point.created,
      closed: point.closed,
      date: formatDay(point.date),
    });
  }

  private line(pick: (point: TrendPoint) => number): string {
    const points = this.points();
    if (points.length === 0) {
      return '';
    }
    return points
      .map((point, index) => `${index === 0 ? 'M' : 'L'} ${this.xOf(index)} ${this.yOf(pick(point))}`)
      .join(' ');
  }

  private xOf(index: number): number {
    const count = this.points().length;
    return count <= 1 ? this.width / 2 : (index / (count - 1)) * this.width;
  }

  private yOf(value: number): number {
    // 8px of headroom keeps the peak marker from touching the top edge.
    const usable = this.height - 10;
    return this.height - (value / this.max()) * usable;
  }
}

function formatDay(value: string | undefined): string {
  if (!value) {
    return '';
  }
  return new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short' }).format(
    new Date(value),
  );
}

export const CHARTS = [BarChartComponent, DonutChartComponent, TrendChartComponent] as const;
