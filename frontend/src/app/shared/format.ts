import { Pipe, PipeTransform } from '@angular/core';

const DATE_TIME = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

const DATE_ONLY = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
});

/** 20/08/2026 14:32 */
@Pipe({ name: 'dateTime' })
export class DateTimePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return value ? DATE_TIME.format(new Date(value)) : '—';
  }
}

/** 20 Aug 2026 */
@Pipe({ name: 'dateOnly' })
export class DateOnlyPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return value ? DATE_ONLY.format(new Date(value)) : '—';
  }
}

/** "3 h ago", "in 2 d" — deadlines read better as a distance than as a timestamp. */
@Pipe({ name: 'relativeTime' })
export class RelativeTimePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) {
      return '—';
    }
    const deltaMs = new Date(value).getTime() - Date.now();
    const past = deltaMs < 0;
    const minutes = Math.round(Math.abs(deltaMs) / 60000);

    let text: string;
    if (minutes < 1) {
      return 'just now';
    } else if (minutes < 60) {
      text = `${minutes} min`;
    } else if (minutes < 60 * 48) {
      text = `${Math.round(minutes / 60)} h`;
    } else {
      text = `${Math.round(minutes / 1440)} d`;
    }
    return past ? `${text} ago` : `in ${text}`;
  }
}

/** 0.82 -> "82%" */
@Pipe({ name: 'percent1' })
export class PercentPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    return value === null || value === undefined ? '—' : `${Math.round(value * 100)}%`;
  }
}

export const FORMAT_PIPES = [DateTimePipe, DateOnlyPipe, RelativeTimePipe, PercentPipe] as const;
